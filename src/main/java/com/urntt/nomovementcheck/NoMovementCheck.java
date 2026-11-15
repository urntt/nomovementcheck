package com.urntt.nomovementcheck;

import com.urntt.nomovementcheck.command.NoMovementCheckCommand;
import com.urntt.nomovementcheck.config.NoMovementCheckConfig;
import com.urntt.nomovementcheck.network.NoMovementCheckNetworking;
import com.urntt.nomovementcheck.network.NoMovementCheckPayloads;
import java.util.Objects;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionCheck;
import org.jspecify.annotations.Nullable;

public final class NoMovementCheck implements ModInitializer {
	public static final String MOD_ID = "nomovementcheck";

	/**
	 * What a player needs to run the command or use the settings screen: permission level 4 (owners).
	 */
	public static final PermissionCheck REQUIRED_PERMISSION = Commands.LEVEL_OWNERS;

	/**
	 * The squared length, in blocks, of the longest single move of an exempt player or vehicle that the server
	 * still simulates along its path. Longer moves are applied as teleports.
	 *
	 * <p>Vanilla simulates every accepted move so that blocks along the way take effect, and the collision sweep of
	 * that simulation covers the whole displacement. Its speed checks keep the displacement short. Without them a
	 * single packet could make the server sweep millions of blocks, so far moves skip the simulation. The value is
	 * the larger of the two vanilla per-packet limits (the one for elytra flight), so every move vanilla would have
	 * accepted is still simulated exactly as before.
	 */
	public static final double MAX_SIMULATED_DISTANCE_SQR = 300.0;

	private static @Nullable NoMovementCheckConfig config;

	@Override
	public void onInitialize() {
		NoMovementCheckPayloads.register();
		NoMovementCheckNetworking.register();
		CommandRegistrationCallback.EVENT.register(
				(dispatcher, buildContext, selection) -> NoMovementCheckCommand.register(dispatcher));

		// The settings belong to the running server: they are read when it starts and dropped when it stops.
		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			NoMovementCheckConfig loaded = NoMovementCheckConfig.load(NoMovementCheckConfig.defaultPath());
			loaded.setChangeListener(() -> NoMovementCheckNetworking.sendSettingsToOperators(server));
			config = loaded;
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> config = null);
	}

	/**
	 * Returns the settings of the running server. Only call this on the server thread.
	 */
	public static NoMovementCheckConfig config() {
		return Objects.requireNonNull(config, "no server is running");
	}

	/**
	 * Returns whether the server currently trusts the player's movement instead of checking it.
	 */
	public static boolean isExempt(final ServerPlayer player) {
		NoMovementCheckConfig current = config;
		return current != null && current.isExempt(player.getUUID());
	}
}
