package com.urntt.nomovementcheck.network;

import com.urntt.nomovementcheck.NoMovementCheck;
import com.urntt.nomovementcheck.config.NoMovementCheckConfig;
import com.urntt.nomovementcheck.config.NoMovementCheckConfig.PlayerOverride;
import com.urntt.nomovementcheck.config.NoMovementCheckConfig.PlayerRef;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.StringUtil;

/**
 * The server side of the settings screen. Each handler checks the sender's permission and then changes the
 * settings through {@link NoMovementCheckConfig}, exactly like the command does.
 */
public final class NoMovementCheckNetworking {
	private NoMovementCheckNetworking() {
	}

	public static void register() {
		ServerPlayNetworking.registerGlobalReceiver(NoMovementCheckPayloads.RequestSettings.TYPE,
				(payload, context) -> ifPermitted(context.player(), config -> sendSettings(context.player(), config)));
		ServerPlayNetworking.registerGlobalReceiver(NoMovementCheckPayloads.SetEnabled.TYPE,
				(payload, context) -> ifPermitted(context.player(), config -> config.setEnabled(payload.enabled())));
		ServerPlayNetworking.registerGlobalReceiver(NoMovementCheckPayloads.SetDefault.TYPE,
				(payload, context) -> ifPermitted(context.player(), config -> config.setAppliesByDefault(payload.appliesByDefault())));
		ServerPlayNetworking.registerGlobalReceiver(NoMovementCheckPayloads.SetPlayer.TYPE,
				(payload, context) -> ifPermitted(context.player(), config -> setPlayer(context.server(), config, payload)));
		ServerPlayNetworking.registerGlobalReceiver(NoMovementCheckPayloads.AddPlayer.TYPE,
				(payload, context) -> ifPermitted(context.player(), config -> addPlayer(context.player(), config, payload.name())));
	}

	/**
	 * Sends the current settings to every online player who has the mod and may change the settings, so open
	 * settings screens stay up to date.
	 */
	public static void sendSettingsToOperators(final MinecraftServer server) {
		NoMovementCheckConfig config = NoMovementCheck.config();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (ServerPlayNetworking.canSend(player, NoMovementCheckPayloads.Settings.TYPE) && mayConfigure(player)) {
				sendSettings(player, config);
			}
		}
	}

	private static boolean mayConfigure(final ServerPlayer player) {
		return NoMovementCheck.REQUIRED_PERMISSION.check(player.permissions());
	}

	private static void ifPermitted(final ServerPlayer player, final Consumer<NoMovementCheckConfig> action) {
		if (mayConfigure(player)) {
			action.accept(NoMovementCheck.config());
		} else {
			ServerPlayNetworking.send(player, NoMovementCheckPayloads.Denied.INSTANCE);
		}
	}

	private static void sendSettings(final ServerPlayer player, final NoMovementCheckConfig config) {
		ServerPlayNetworking.send(player,
				new NoMovementCheckPayloads.Settings(config.isEnabled(), config.appliesByDefault(), config.overrides()));
	}

	private static void setPlayer(
			final MinecraftServer server, final NoMovementCheckConfig config, final NoMovementCheckPayloads.SetPlayer payload) {
		UUID id = payload.id();
		if (payload.value().isEmpty()) {
			config.removeOverrides(List.of(id));
			return;
		}

		config.setOverrides(List.of(new PlayerRef(id, displayName(server, config, payload))), payload.value().get());
	}

	/**
	 * Chooses the name to store for a player. The name the client sent is only used for players the server knows
	 * nothing about.
	 */
	private static String displayName(
			final MinecraftServer server, final NoMovementCheckConfig config, final NoMovementCheckPayloads.SetPlayer payload) {
		ServerPlayer online = server.getPlayerList().getPlayer(payload.id());
		if (online != null) {
			return online.getPlainTextName();
		}

		return config.override(payload.id())
				.map(PlayerOverride::name)
				.orElseGet(() -> payload.name().isBlank() ? payload.id().toString() : payload.name());
	}

	/**
	 * Gives the named player a setting of their own, opposite to the default, unless they already have one. The
	 * name is resolved the same way the command resolves player names, so offline players work too.
	 */
	private static void addPlayer(final ServerPlayer sender, final NoMovementCheckConfig config, final String name) {
		Optional<NameAndId> resolved = name.isBlank() || !StringUtil.isValidPlayerName(name)
				? Optional.empty()
				: sender.level().getServer().services().nameToIdCache().get(name);
		if (resolved.isEmpty()) {
			ServerPlayNetworking.send(sender, new NoMovementCheckPayloads.PlayerNotFound(name));
			return;
		}

		NameAndId player = resolved.get();
		boolean added = config.override(player.id()).isEmpty()
				&& config.setOverrides(List.of(new PlayerRef(player.id(), player.name())), !config.appliesByDefault()) > 0;
		if (!added) {
			// Nothing changed, so no update was sent. Answer anyway, so the screen can show the existing entry.
			sendSettings(sender, config);
		}
	}
}
