package com.urntt.nomovementcheck.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.urntt.nomovementcheck.NoMovementCheck;
import com.urntt.nomovementcheck.client.screen.NoMovementCheckScreen;
import com.urntt.nomovementcheck.network.NoMovementCheckPayloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The optional client part: a key that asks the server for its settings and opens the settings screen once the
 * server answers. The server only answers players who are allowed to change the settings.
 */
public final class NoMovementCheckClient implements ClientModInitializer {
	public static final String OPEN_SETTINGS_KEY_NAME = "key.nomovementcheck.open_settings";

	/** Shown when the key is pressed on a server that does not run the mod. */
	public static final Component SERVER_MISSING = Component.translatable("nomovementcheck.message.server_missing");
	/** Shown when the server refuses to show or change the settings. */
	public static final Component NO_PERMISSION = Component.translatable("nomovementcheck.message.no_permission");

	/** Whether the key was pressed and the server has not answered yet. Only used on the client thread. */
	private static boolean awaitingSettings;

	@Override
	public void onInitializeClient() {
		KeyMapping.Category category =
				KeyMapping.Category.register(Identifier.fromNamespaceAndPath(NoMovementCheck.MOD_ID, "general"));
		KeyMapping openSettingsKey = KeyMappingHelper.registerKeyMapping(
				new KeyMapping(OPEN_SETTINGS_KEY_NAME, InputConstants.UNKNOWN.getValue(), category));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openSettingsKey.consumeClick()) {
				requestSettings(client);
			}
		});

		ClientPlayNetworking.registerGlobalReceiver(NoMovementCheckPayloads.Settings.TYPE,
				(payload, context) -> onSettings(context.client(), payload));
		ClientPlayNetworking.registerGlobalReceiver(NoMovementCheckPayloads.Denied.TYPE,
				(payload, context) -> onDenied(context.client()));
		ClientPlayNetworking.registerGlobalReceiver(NoMovementCheckPayloads.PlayerNotFound.TYPE,
				(payload, context) -> onPlayerNotFound(context.client(), payload.name()));
		// The disconnect event may arrive on another thread.
		ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> client.execute(() -> awaitingSettings = false));
	}

	private static void requestSettings(final Minecraft client) {
		if (client.player == null) {
			return;
		}

		if (!ClientPlayNetworking.canSend(NoMovementCheckPayloads.RequestSettings.TYPE)) {
			client.player.sendOverlayMessage(SERVER_MISSING);
			return;
		}

		awaitingSettings = true;
		ClientPlayNetworking.send(NoMovementCheckPayloads.RequestSettings.INSTANCE);
	}

	/**
	 * The server sends its settings as the answer to the key press, and again whenever they change. An open
	 * settings screen is updated; otherwise the screen only opens if the player asked for it.
	 */
	private static void onSettings(final Minecraft client, final NoMovementCheckPayloads.Settings settings) {
		if (client.gui.screen() instanceof NoMovementCheckScreen screen) {
			screen.showSettings(settings);
		} else if (awaitingSettings) {
			client.gui.setScreen(new NoMovementCheckScreen(client.gui.screen(), client.options, settings));
		}
		awaitingSettings = false;
	}

	private static void onDenied(final Minecraft client) {
		awaitingSettings = false;
		if (client.gui.screen() instanceof NoMovementCheckScreen screen) {
			screen.onClose();
		}
		if (client.player != null) {
			client.player.sendOverlayMessage(NO_PERMISSION);
		}
	}

	private static void onPlayerNotFound(final Minecraft client, final String name) {
		if (client.gui.screen() instanceof NoMovementCheckScreen screen) {
			screen.showPlayerNotFound(name);
		}
	}
}
