package com.urntt.nomovementcheck.network;

import com.urntt.nomovementcheck.NoMovementCheck;
import com.urntt.nomovementcheck.config.NoMovementCheckConfig.PlayerOverride;
import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * The packets exchanged between the server and clients that also have the mod. They carry the settings screen:
 * the client asks for the settings and sends changes, the server answers with the current state.
 *
 * <p>The server checks the sender's permission for every serverbound payload.
 */
public final class NoMovementCheckPayloads {
	/**
	 * The longest player name a client may send.
	 */
	public static final int MAX_NAME_LENGTH = 16;

	private static final StreamCodec<ByteBuf, String> TYPED_NAME = ByteBufCodecs.stringUtf8(MAX_NAME_LENGTH);
	private static final StreamCodec<ByteBuf, PlayerOverride> OVERRIDE = StreamCodec.composite(
			UUIDUtil.STREAM_CODEC, PlayerOverride::id,
			ByteBufCodecs.STRING_UTF8, PlayerOverride::name,
			ByteBufCodecs.BOOL, PlayerOverride::value,
			PlayerOverride::new);

	private NoMovementCheckPayloads() {
	}

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(RequestSettings.TYPE, RequestSettings.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(SetEnabled.TYPE, SetEnabled.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(SetDefault.TYPE, SetDefault.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(SetPlayer.TYPE, SetPlayer.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(AddPlayer.TYPE, AddPlayer.CODEC);

		PayloadTypeRegistry.clientboundPlay().register(Settings.TYPE, Settings.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Denied.TYPE, Denied.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(PlayerNotFound.TYPE, PlayerNotFound.CODEC);
	}

	private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> payloadType(final String path) {
		return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(NoMovementCheck.MOD_ID, path));
	}

	/**
	 * Serverbound: asks for the current settings, to open the settings screen.
	 */
	public record RequestSettings() implements CustomPacketPayload {
		public static final RequestSettings INSTANCE = new RequestSettings();
		public static final CustomPacketPayload.Type<RequestSettings> TYPE = payloadType("request_settings");
		public static final StreamCodec<ByteBuf, RequestSettings> CODEC = StreamCodec.unit(INSTANCE);

		@Override
		public CustomPacketPayload.Type<RequestSettings> type() {
			return TYPE;
		}
	}

	/**
	 * Serverbound: turns the whole mod on or off.
	 */
	public record SetEnabled(boolean enabled) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<SetEnabled> TYPE = payloadType("set_enabled");
		public static final StreamCodec<ByteBuf, SetEnabled> CODEC =
				StreamCodec.composite(ByteBufCodecs.BOOL, SetEnabled::enabled, SetEnabled::new);

		@Override
		public CustomPacketPayload.Type<SetEnabled> type() {
			return TYPE;
		}
	}

	/**
	 * Serverbound: sets whether the mod applies to players without a setting of their own.
	 */
	public record SetDefault(boolean appliesByDefault) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<SetDefault> TYPE = payloadType("set_default");
		public static final StreamCodec<ByteBuf, SetDefault> CODEC =
				StreamCodec.composite(ByteBufCodecs.BOOL, SetDefault::appliesByDefault, SetDefault::new);

		@Override
		public CustomPacketPayload.Type<SetDefault> type() {
			return TYPE;
		}
	}

	/**
	 * Serverbound: sets a player's own setting, or removes it when {@code value} is empty.
	 */
	public record SetPlayer(UUID id, String name, Optional<Boolean> value) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<SetPlayer> TYPE = payloadType("set_player");
		public static final StreamCodec<ByteBuf, SetPlayer> CODEC = StreamCodec.composite(
				UUIDUtil.STREAM_CODEC, SetPlayer::id,
				TYPED_NAME, SetPlayer::name,
				ByteBufCodecs.optional(ByteBufCodecs.BOOL), SetPlayer::value,
				SetPlayer::new);

		@Override
		public CustomPacketPayload.Type<SetPlayer> type() {
			return TYPE;
		}
	}

	/**
	 * Serverbound: gives the named player, who may be offline, a setting of their own.
	 */
	public record AddPlayer(String name) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<AddPlayer> TYPE = payloadType("add_player");
		public static final StreamCodec<ByteBuf, AddPlayer> CODEC =
				StreamCodec.composite(TYPED_NAME, AddPlayer::name, AddPlayer::new);

		@Override
		public CustomPacketPayload.Type<AddPlayer> type() {
			return TYPE;
		}
	}

	/**
	 * Clientbound: the current settings. Sent as the answer to {@link RequestSettings} and whenever the settings
	 * change.
	 */
	public record Settings(boolean enabled, boolean appliesByDefault, List<PlayerOverride> overrides)
			implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<Settings> TYPE = payloadType("settings");
		public static final StreamCodec<ByteBuf, Settings> CODEC = StreamCodec.composite(
				ByteBufCodecs.BOOL, Settings::enabled,
				ByteBufCodecs.BOOL, Settings::appliesByDefault,
				OVERRIDE.apply(ByteBufCodecs.list()), Settings::overrides,
				Settings::new);

		@Override
		public CustomPacketPayload.Type<Settings> type() {
			return TYPE;
		}
	}

	/**
	 * Clientbound: the player lacks the permission to see or change the settings.
	 */
	public record Denied() implements CustomPacketPayload {
		public static final Denied INSTANCE = new Denied();
		public static final CustomPacketPayload.Type<Denied> TYPE = payloadType("denied");
		public static final StreamCodec<ByteBuf, Denied> CODEC = StreamCodec.unit(INSTANCE);

		@Override
		public CustomPacketPayload.Type<Denied> type() {
			return TYPE;
		}
	}

	/**
	 * Clientbound: the name sent with {@link AddPlayer} does not belong to any known player.
	 */
	public record PlayerNotFound(String name) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<PlayerNotFound> TYPE = payloadType("player_not_found");
		public static final StreamCodec<ByteBuf, PlayerNotFound> CODEC =
				StreamCodec.composite(TYPED_NAME, PlayerNotFound::name, PlayerNotFound::new);

		@Override
		public CustomPacketPayload.Type<PlayerNotFound> type() {
			return TYPE;
		}
	}
}
