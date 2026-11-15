package com.urntt.nomovementcheck.client.screen;

import com.urntt.nomovementcheck.config.NoMovementCheckConfig.PlayerOverride;
import com.urntt.nomovementcheck.network.NoMovementCheckPayloads;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Shows the server's settings and lets an operator change them. The screen holds no settings of its own: every
 * change is sent to the server, and the screen redraws when the server answers with the new state.
 */
public final class NoMovementCheckScreen extends OptionsSubScreen {
	private static final Component TITLE = Component.translatable("options.nomovementcheck.title");
	private static final Component ENABLED = Component.translatable("options.nomovementcheck.enabled");
	private static final Tooltip ENABLED_TOOLTIP = Tooltip.create(Component.translatable("options.nomovementcheck.enabled.tooltip"));
	private static final Component DEFAULT = Component.translatable("options.nomovementcheck.default");
	private static final Tooltip DEFAULT_TOOLTIP = Tooltip.create(Component.translatable("options.nomovementcheck.default.tooltip"));
	private static final Component CHECKS_REMOVED = Component.translatable("options.nomovementcheck.value.true");
	private static final Component CHECKS_KEPT = Component.translatable("options.nomovementcheck.value.false");
	private static final String FOLLOWS_DEFAULT_KEY = "options.nomovementcheck.player.default";
	private static final Component PLAYERS = Component.translatable("options.nomovementcheck.players");
	private static final Component ADD = Component.translatable("options.nomovementcheck.add");
	private static final Component ADD_HINT = Component.translatable("options.nomovementcheck.add.hint");
	private static final Tooltip ADD_TOOLTIP = Tooltip.create(Component.translatable("options.nomovementcheck.add.tooltip"));
	private static final String PLAYER_NOT_FOUND_KEY = "options.nomovementcheck.player_not_found";

	/** Width of one of the two widgets in a row of the options list. */
	private static final int SMALL_WIDGET_WIDTH = 150;
	private static final int BIG_WIDGET_WIDTH = 310;
	private static final int WIDGET_HEIGHT = 20;

	/** The three states a player can be in: following the default, or one of the two settings of their own. */
	private static final List<Optional<Boolean>> PLAYER_VALUES = List.of(Optional.empty(), Optional.of(true), Optional.of(false));

	private NoMovementCheckPayloads.Settings settings;
	private String typedName = "";
	private @Nullable Component error;
	private @Nullable EditBox nameBox;
	/** What the screen this one replaces looked like, restored once the widgets exist. */
	private double initialScrollAmount;
	private boolean initiallyFocusNameBox;

	public NoMovementCheckScreen(
			final @Nullable Screen lastScreen, final Options options, final NoMovementCheckPayloads.Settings settings) {
		super(lastScreen, options, TITLE);
		this.settings = settings;
	}

	/**
	 * Shows the state the server just sent.
	 */
	public void showSettings(final NoMovementCheckPayloads.Settings settings) {
		this.settings = settings;
		this.error = null;
		this.redraw();
	}

	/**
	 * Tells the operator that the name they asked to add does not belong to any known player.
	 */
	public void showPlayerNotFound(final String name) {
		this.error = Component.translatable(PLAYER_NOT_FOUND_KEY, name).withStyle(ChatFormatting.RED);
		this.redraw();
	}

	@Override
	protected void init() {
		super.init();
		// The message about a failed addition is the last row, so scroll down to it.
		this.list.setScrollAmount(this.error != null ? this.list.maxScrollAmount() : this.initialScrollAmount);
		if (this.initiallyFocusNameBox) {
			this.focusInList(this.nameBox);
		}
	}

	/**
	 * Does nothing: unlike the vanilla option screens, this one changes no client options that would need saving.
	 */
	@Override
	public void removed() {
	}

	@Override
	protected void addOptions() {
		this.list.addBig(CycleButton.onOffBuilder(this.settings.enabled())
				.withTooltip(value -> ENABLED_TOOLTIP)
				.create(ENABLED, (button, value) -> ClientPlayNetworking.send(new NoMovementCheckPayloads.SetEnabled(value))));
		this.list.addBig(CycleButton.booleanBuilder(CHECKS_REMOVED, CHECKS_KEPT, this.settings.appliesByDefault())
				.withTooltip(value -> DEFAULT_TOOLTIP)
				.create(DEFAULT, (button, value) -> ClientPlayNetworking.send(new NoMovementCheckPayloads.SetDefault(value))));

		this.list.addHeader(PLAYERS);
		for (PlayerRow row : this.playerRows()) {
			Component name = Component.literal(row.name());
			CycleButton<Optional<Boolean>> valueButton = CycleButton.<Optional<Boolean>>builder(this::describe, row.value())
					.withValues(PLAYER_VALUES)
					.displayOnlyValue()
					.create(name, (button, value) -> ClientPlayNetworking.send(
							new NoMovementCheckPayloads.SetPlayer(row.id(), row.name(), value)));
			this.list.addSmall(new StringWidget(SMALL_WIDGET_WIDTH, WIDGET_HEIGHT, name, this.font), valueButton);
		}

		this.nameBox = new EditBox(this.font, SMALL_WIDGET_WIDTH, WIDGET_HEIGHT, ADD_HINT);
		this.nameBox.setMaxLength(NoMovementCheckPayloads.MAX_NAME_LENGTH);
		this.nameBox.setHint(ADD_HINT.copy().withStyle(EditBox.SEARCH_HINT_STYLE));
		this.nameBox.setValue(this.typedName);
		this.nameBox.setResponder(value -> this.typedName = value);
		this.list.addSmall(
				this.nameBox,
				Button.builder(ADD, button -> this.addTypedPlayer()).width(SMALL_WIDGET_WIDTH).tooltip(ADD_TOOLTIP).build());

		if (this.error != null) {
			this.list.addBig(new StringWidget(BIG_WIDGET_WIDTH, WIDGET_HEIGHT, this.error, this.font));
		}
	}

	@Override
	public boolean keyPressed(final KeyEvent event) {
		if (event.isConfirmation() && this.nameBox != null && this.nameBox.isFocused()) {
			this.addTypedPlayer();
			return true;
		}
		return super.keyPressed(event);
	}

	private void addTypedPlayer() {
		String name = this.typedName.strip();
		if (!name.isEmpty()) {
			// The server answers with either the new settings or "not found", and both redraw the screen.
			this.typedName = "";
			ClientPlayNetworking.send(new NoMovementCheckPayloads.AddPlayer(name));
		}
	}

	private Component describe(final Optional<Boolean> value) {
		return value
				.map(NoMovementCheckScreen::describe)
				.orElseGet(() -> Component.translatable(FOLLOWS_DEFAULT_KEY, describe(this.settings.appliesByDefault())));
	}

	private static Component describe(final boolean value) {
		return value ? CHECKS_REMOVED : CHECKS_KEPT;
	}

	/**
	 * Lists every online player together with every player that has a setting of their own, ordered by name.
	 */
	private List<PlayerRow> playerRows() {
		Map<UUID, PlayerRow> rows = new HashMap<>();
		ClientPacketListener connection = this.minecraft.getConnection();
		if (connection != null) {
			for (PlayerInfo info : connection.getOnlinePlayers()) {
				rows.put(info.getProfile().id(), new PlayerRow(info.getProfile().id(), info.getProfile().name(), Optional.empty()));
			}
		}

		for (PlayerOverride override : this.settings.overrides()) {
			// A name from the tab list is current, the stored one may be outdated.
			PlayerRow online = rows.get(override.id());
			String name = online != null ? online.name() : override.name();
			rows.put(override.id(), new PlayerRow(override.id(), name, Optional.of(override.value())));
		}

		List<PlayerRow> sorted = new ArrayList<>(rows.values());
		sorted.sort(Comparator.comparing(PlayerRow::name, String.CASE_INSENSITIVE_ORDER).thenComparing(PlayerRow::id));
		return sorted;
	}

	/**
	 * Shows the current state by replacing this screen with a new one, which keeps the scroll position, the typed
	 * name, and the focus of the name box. The widgets live in the layout the vanilla base class creates once per
	 * screen, so they cannot be rebuilt in place.
	 */
	private void redraw() {
		NoMovementCheckScreen replacement = new NoMovementCheckScreen(this.lastScreen, this.options, this.settings);
		replacement.typedName = this.typedName;
		replacement.error = this.error;
		replacement.initialScrollAmount = this.list != null ? this.list.scrollAmount() : 0.0;
		replacement.initiallyFocusNameBox = this.nameBox != null && this.nameBox.isFocused();
		this.minecraft.gui.setScreen(replacement);
	}

	private void focusInList(final AbstractWidget widget) {
		for (ContainerObjectSelectionList.Entry<?> entry : this.list.children()) {
			if (entry.children().contains(widget)) {
				this.setFocused(this.list);
				this.list.setFocused(entry);
				entry.setFocused(widget);
				return;
			}
		}
	}

	private record PlayerRow(UUID id, String name, Optional<Boolean> value) {
	}
}
