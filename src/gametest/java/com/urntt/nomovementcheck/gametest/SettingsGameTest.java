package com.urntt.nomovementcheck.gametest;

import static com.urntt.nomovementcheck.gametest.TestSupport.LOGGER;
import static com.urntt.nomovementcheck.gametest.TestSupport.check;

import com.mojang.blaze3d.platform.InputConstants;
import com.urntt.nomovementcheck.NoMovementCheck;
import com.urntt.nomovementcheck.client.NoMovementCheckClient;
import com.urntt.nomovementcheck.client.screen.NoMovementCheckScreen;
import com.urntt.nomovementcheck.config.NoMovementCheckConfig;
import com.urntt.nomovementcheck.config.NoMovementCheckConfig.PlayerOverride;
import java.nio.file.Files;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.NameAndId;

/**
 * Checks the settings on a dedicated server: the defaults of a fresh installation, the command and its permission
 * level, the key binding, the settings screen, and the saved file.
 *
 * <p>This test runs first and expects the game's config file not to exist yet.
 */
@SuppressWarnings("UnstableApiUsage")
public final class SettingsGameTest implements FabricClientGameTest {
	private static final String COMMAND = NoMovementCheck.MOD_ID;
	private static final MouseButtonInfo LEFT_CLICK = new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0);

	private ClientGameTestContext context;
	private TestDedicatedServerContext server;
	private TestDedicatedServerConnection connection;
	private String playerName;
	private UUID playerId;

	@Override
	public void runTest(final ClientGameTestContext context) {
		this.context = context;
		check(Files.notExists(NoMovementCheckConfig.defaultPath()),
				"the config file should not exist before the first server starts; this test must run first");

		KeyMapping settingsKey =
				Objects.requireNonNull(KeyMapping.get(NoMovementCheckClient.OPEN_SETTINGS_KEY_NAME), "settings key mapping");
		check(settingsKey.isUnbound(), "the settings key should be unbound by default");
		context.runOnClient(client -> {
			settingsKey.setKey(InputConstants.getKey("key.keyboard.j"));
			KeyMapping.resetMapping();
		});

		try (TestDedicatedServerContext server = context.worldBuilder().createServer();
				TestDedicatedServerConnection connection = server.connect()) {
			this.server = server;
			this.connection = connection;
			this.playerName = context.computeOnClient(client -> client.getGameProfile().name());
			this.playerId = context.computeOnClient(client -> client.getGameProfile().id());

			this.checkFreshInstallation();
			this.checkAccessWithoutPermission(settingsKey);
			this.checkCommandAsOwner();
			this.checkSettingsScreen(settingsKey);
			this.checkConsoleCommands();
			this.checkAccessAfterLosingPermission(settingsKey);
			this.checkSavedFile();
		} finally {
			context.runOnClient(client -> {
				settingsKey.setKey(settingsKey.getDefaultKey());
				KeyMapping.resetMapping();
			});
		}
	}

	private void checkFreshInstallation() {
		NoMovementCheckConfig saved = NoMovementCheckConfig.load(NoMovementCheckConfig.defaultPath());
		check(!saved.isEnabled(), "a fresh installation should be disabled");
		check(saved.appliesByDefault(), "a fresh installation should apply to players without their own setting");
		check(saved.overrides().isEmpty(), "a fresh installation should have no player settings");
		check(!this.onServer(server -> NoMovementCheck.config().isEnabled()), "the running server should be disabled by default");
		check(!this.isExempt(), "nobody should be exempt on a fresh installation");
	}

	/**
	 * A player below permission level 4 neither sees nor runs the command, and the key only shows a message.
	 */
	private void checkAccessWithoutPermission(final KeyMapping settingsKey) {
		check(!this.clientKnowsCommand(), "a player without permission should not be offered the command");
		this.runAsPlayer(COMMAND + " enabled true");
		check(!this.onServer(server -> NoMovementCheck.config().isEnabled()), "a player without permission should not run the command");
		this.pressSettingsKey(settingsKey);
		check(!this.isSettingsScreenOpen(), "the settings screen should not open without permission");
		this.context.takeScreenshot("nomovementcheck-no-permission");

		// Level 3 (admins) is still below the required level 4.
		this.setPermission(Optional.of(LevelBasedPermissionSet.ADMIN));
		check(!this.clientKnowsCommand(), "a level 3 operator should not be offered the command");
		this.runAsPlayer(COMMAND + " enabled true");
		check(!this.onServer(server -> NoMovementCheck.config().isEnabled()), "a level 3 operator should not run the command");
		this.pressSettingsKey(settingsKey);
		check(!this.isSettingsScreenOpen(), "the settings screen should not open for a level 3 operator");
	}

	private void checkCommandAsOwner() {
		this.setPermission(Optional.of(LevelBasedPermissionSet.OWNER));
		check(this.clientKnowsCommand(), "a level 4 operator should be offered the command");
		this.runAsPlayer(COMMAND + " enabled true");
		check(this.onServer(server -> NoMovementCheck.config().isEnabled()), "a level 4 operator should run the command");
		check(this.isExempt(), "enabling the mod should make players without their own setting exempt");
		this.runAsPlayer(COMMAND + " enabled false");
		check(!this.isExempt(), "disabling the mod should end every exemption");
	}

	private void checkSettingsScreen(final KeyMapping settingsKey) {
		this.pressSettingsKey(settingsKey);
		this.context.waitForScreen(NoMovementCheckScreen.class);
		check(Boolean.FALSE.equals(this.onClient(client -> cycleButton(client, "options.nomovementcheck.enabled").getValue())),
				"the screen should show the mod as disabled");
		this.context.takeScreenshot("nomovementcheck-settings");

		this.press(client -> cycleButton(client, "options.nomovementcheck.enabled"));
		check(this.onServer(server -> NoMovementCheck.config().isEnabled()), "the screen should enable the mod on the server");
		check(NoMovementCheckConfig.load(NoMovementCheckConfig.defaultPath()).isEnabled(), "changes from the screen should be saved");
		check(Boolean.TRUE.equals(this.onClient(client -> cycleButton(client, "options.nomovementcheck.enabled").getValue())),
				"the screen should show the state the server confirmed");

		this.press(client -> cycleButton(client, "options.nomovementcheck.default"));
		check(!this.onServer(server -> NoMovementCheck.config().appliesByDefault()), "the screen should change the default on the server");
		check(!this.isExempt(), "with the default off, a player without their own setting should not be exempt");

		// The player's own row cycles: default -> removed -> kept -> default.
		this.press(client -> playerButton(client, this.playerName));
		check(this.ownSetting().equals(Optional.of(true)), "the first click should give the player their own setting: true");
		check(this.isExempt(), "a player set to true should be exempt even with the default off");
		this.press(client -> playerButton(client, this.playerName));
		check(this.ownSetting().equals(Optional.of(false)), "the second click should change the player's setting to false");
		this.press(client -> playerButton(client, this.playerName));
		check(this.ownSetting().isEmpty(), "the third click should remove the player's own setting");

		// Players who are not online are added by name and start opposite to the default.
		this.addByName("Alex");
		Optional<PlayerOverride> alex = this.onServer(server -> NoMovementCheck.config().overrides().stream()
				.filter(override -> override.name().equals("Alex"))
				.findFirst());
		check(alex.isPresent() && alex.get().value(), "adding a name should give that player a setting opposite to the default");
		check(this.onClient(client -> hasText(client, "Alex")), "the screen should list the added player");

		this.addByName("not a name");
		check(this.onClient(client -> hasText(client, Component.translatable("options.nomovementcheck.player_not_found", "not a name").getString())),
				"the screen should say that an invalid name was not found");
		this.context.takeScreenshot("nomovementcheck-settings-changed");

		// Changes made elsewhere reach an open screen.
		this.server.runCommand(COMMAND + " player Alex reset");
		this.sync();
		check(!this.onClient(client -> hasText(client, "Alex")), "the screen should follow changes made with the command");

		this.context.runOnClient(client -> client.gui.screen().onClose());
		check(!this.isSettingsScreenOpen(), "closing the screen should return to the game");

		this.setLanguage("zh_cn");
		this.pressSettingsKey(settingsKey);
		this.context.waitForScreen(NoMovementCheckScreen.class);
		this.context.takeScreenshot("nomovementcheck-settings-zh_cn");
		this.context.runOnClient(client -> client.gui.screen().onClose());
		this.setLanguage("en_us");
	}

	/**
	 * Runs the command from the server console, which always has the permission.
	 */
	private void checkConsoleCommands() {
		this.server.runCommand(COMMAND + " enabled true");
		this.server.runCommand(COMMAND + " default true");
		check(this.isExempt(), "enabled with the default on, the player should be exempt");

		this.server.runCommand(COMMAND + " player @a false");
		check(this.ownSetting().equals(Optional.of(false)), "a selector should set every selected player");
		check(!this.isExempt(), "a player set to false should not be exempt");

		this.server.runCommand(COMMAND + " player " + this.playerName + " true");
		check(this.ownSetting().equals(Optional.of(true)), "a name should set that player");
		this.server.runCommand(COMMAND + " player " + this.playerName + " reset");
		check(this.ownSetting().isEmpty(), "reset should remove the player's own setting");

		this.server.runCommand(COMMAND + " player Alex false");
		this.server.runCommand(COMMAND + " player @a true");
		check(this.onServer(server -> NoMovementCheck.config().overrides().size()) == 2, "offline players should be set by name");
		this.server.runCommand(COMMAND + " player clear");
		check(this.onServer(server -> NoMovementCheck.config().overrides().isEmpty()), "clear should remove every player's own setting");

		// These only print, but must not fail.
		this.server.runCommand(COMMAND);
		this.server.runCommand(COMMAND + " enabled");
		this.server.runCommand(COMMAND + " default");
		this.server.runCommand(COMMAND + " player @a");
		this.server.runCommand(COMMAND + " player list");
	}

	private void checkAccessAfterLosingPermission(final KeyMapping settingsKey) {
		this.setPermission(Optional.empty());
		check(!this.clientKnowsCommand(), "a player who lost the permission should no longer be offered the command");
		this.pressSettingsKey(settingsKey);
		check(!this.isSettingsScreenOpen(), "the settings screen should not open after losing the permission");
	}

	private void checkSavedFile() {
		this.server.runCommand(COMMAND + " default false");
		this.server.runCommand(COMMAND + " player " + this.playerName + " true");
		NoMovementCheckConfig saved = NoMovementCheckConfig.load(NoMovementCheckConfig.defaultPath());
		check(saved.isEnabled() && !saved.appliesByDefault(), "the switches should be saved to the config file");
		check(saved.overrides().equals(List.of(new PlayerOverride(this.playerId, this.playerName, true))),
				"the player settings should be saved to the config file, got " + saved.overrides());
		LOGGER.info("Saved settings: enabled={}, default={}, players={}", saved.isEnabled(), saved.appliesByDefault(), saved.overrides());
	}

	// Server and client access

	private <T> T onServer(final Function<MinecraftServer, T> function) {
		return this.server.computeOnServer(function::apply);
	}

	private <T> T onClient(final Function<Minecraft, T> function) {
		return this.context.computeOnClient(function::apply);
	}

	private boolean isExempt() {
		return this.onServer(server -> NoMovementCheck.isExempt(this.connection.getServerPlayer()));
	}

	private Optional<Boolean> ownSetting() {
		return this.onServer(server -> NoMovementCheck.config().override(this.playerId).map(PlayerOverride::value));
	}

	/**
	 * Gives the player the permissions of an operator of that level, or takes the operator status away.
	 */
	private void setPermission(final Optional<LevelBasedPermissionSet> permissions) {
		this.server.runOnServer(server -> {
			NameAndId player = this.connection.getServerPlayer().nameAndId();
			if (permissions.isPresent()) {
				server.getPlayerList().op(player, permissions, Optional.empty());
			} else {
				server.getPlayerList().deop(player);
			}
		});
		this.sync();
	}

	private boolean clientKnowsCommand() {
		return this.onClient(client -> client.getConnection().getCommands().getRoot().getChild(COMMAND) != null);
	}

	private void runAsPlayer(final String command) {
		this.context.runOnClient(client -> client.getConnection().sendCommand(command));
		this.sync();
	}

	private void pressSettingsKey(final KeyMapping settingsKey) {
		this.context.getInput().pressKey(settingsKey);
		this.context.waitTick();
		this.sync();
	}

	/**
	 * Waits until the server has handled what the client sent and the client has handled the answer.
	 */
	private void sync() {
		this.connection.waitForServerboundPackets();
		this.connection.waitForClientboundPackets();
		this.context.waitTick();
	}

	private boolean isSettingsScreenOpen() {
		return this.onClient(client -> client.gui.screen() instanceof NoMovementCheckScreen);
	}

	private void setLanguage(final String code) {
		CompletableFuture<Void> reload = this.context.computeOnClient(client -> {
			client.getLanguageManager().setSelected(code);
			client.options.languageCode = code;
			return client.reloadResourcePacks();
		});
		this.context.waitFor(client -> reload.isDone() && client.gui.overlay() == null, ClientGameTestContext.NO_TIMEOUT);
	}

	// Widgets of the settings screen

	private void press(final Function<Minecraft, AbstractButton> finder) {
		this.context.runOnClient(client -> finder.apply(client).onPress(LEFT_CLICK));
		this.sync();
	}

	private void addByName(final String name) {
		this.context.runOnClient(client -> findWidget(client, EditBox.class, box -> true).setValue(name));
		this.press(client -> findWidget(client, Button.class,
				button -> button.getMessage().getString().equals(Component.translatable("options.nomovementcheck.add").getString())));
	}

	private static CycleButton<?> cycleButton(final Minecraft client, final String nameKey) {
		String name = Component.translatable(nameKey).getString();
		return findWidget(client, CycleButton.class, button -> button.getMessage().getString().startsWith(name + ":"));
	}

	/**
	 * Finds the button in the row that shows the given player name.
	 */
	private static CycleButton<?> playerButton(final Minecraft client, final String playerName) {
		for (ContainerObjectSelectionList.Entry<?> row : rows(client)) {
			boolean namesPlayer = row.children().stream()
					.anyMatch(widget -> widget instanceof StringWidget label && label.getMessage().getString().equals(playerName));
			if (namesPlayer) {
				for (GuiEventListener widget : row.children()) {
					if (widget instanceof CycleButton<?> button) {
						return button;
					}
				}
			}
		}
		throw new AssertionError("no row for player " + playerName);
	}

	private static boolean hasText(final Minecraft client, final String text) {
		for (ContainerObjectSelectionList.Entry<?> row : rows(client)) {
			for (GuiEventListener widget : row.children()) {
				if (widget instanceof StringWidget label && label.getMessage().getString().equals(text)) {
					return true;
				}
			}
		}
		return false;
	}

	private static <T extends AbstractWidget> T findWidget(final Minecraft client, final Class<T> type, final Predicate<T> filter) {
		for (ContainerObjectSelectionList.Entry<?> row : rows(client)) {
			for (GuiEventListener widget : row.children()) {
				if (type.isInstance(widget) && filter.test(type.cast(widget))) {
					return type.cast(widget);
				}
			}
		}
		throw new AssertionError("no matching " + type.getSimpleName() + " on the settings screen");
	}

	private static List<? extends ContainerObjectSelectionList.Entry<?>> rows(final Minecraft client) {
		Screen screen = client.gui.screen();
		check(screen instanceof NoMovementCheckScreen, "the settings screen should be open, but the screen is " + screen);
		for (GuiEventListener child : screen.children()) {
			if (child instanceof ContainerObjectSelectionList<?> list) {
				return list.children();
			}
		}
		throw new AssertionError("the settings screen has no list");
	}
}
