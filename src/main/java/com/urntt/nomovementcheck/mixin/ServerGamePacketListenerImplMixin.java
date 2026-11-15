package com.urntt.nomovementcheck.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.urntt.nomovementcheck.NoMovementCheck;
import net.minecraft.core.PositionAndRotation;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes the server trust the position and movement reported by exempt players. Every handler leaves players that
 * are not exempt untouched, so they keep the vanilla checks.
 *
 * <p>These stay vanilla for everyone: the coordinate range, ignoring movement until a server-initiated teleport is
 * confirmed or until the client has finished loading, and ignoring a passenger's own position packets.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
	@Shadow
	public ServerPlayer player;

	@Shadow
	private static double clampHorizontal(final double value) {
		throw new AssertionError();
	}

	@Shadow
	private static double clampVertical(final double value) {
		throw new AssertionError();
	}

	@Unique
	private boolean nomovementcheck$isExempt() {
		return NoMovementCheck.isExempt(this.player);
	}

	/**
	 * Skips the player speed check ("moved too quickly"), which would set the player back.
	 */
	@ModifyReturnValue(method = "shouldCheckPlayerMovement", at = @At("RETURN"))
	private boolean nomovementcheck$skipPlayerSpeedCheck(final boolean original) {
		return original && !this.nomovementcheck$isExempt();
	}

	/**
	 * Skips the vehicle speed check, the same way vanilla skips it for the owner of a singleplayer world.
	 */
	@ModifyExpressionValue(
			method = "handleMoveVehicle",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;isSingleplayerOwner()Z"))
	private boolean nomovementcheck$skipVehicleSpeedCheck(final boolean original) {
		return original || this.nomovementcheck$isExempt();
	}

	/**
	 * Accepts any difference between the position the server simulated and the one the client reported, for
	 * players and vehicles, so nothing "moved wrongly".
	 */
	@ModifyExpressionValue(
			method = {"handlePlayerPositionChange", "handleMoveVehicle"},
			at = @At(value = "CONSTANT", args = "doubleValue=0.0625"),
			require = 2)
	private double nomovementcheck$allowAnyDeviation(final double original) {
		return this.nomovementcheck$isExempt() ? Double.POSITIVE_INFINITY : original;
	}

	/**
	 * Lets players and vehicles move into blocks, which would otherwise set them back.
	 */
	@Inject(method = "isEntityCollidingWithAnythingNew", at = @At("HEAD"), cancellable = true)
	private void nomovementcheck$skipCollisionCheck(final CallbackInfoReturnable<Boolean> cir) {
		if (this.nomovementcheck$isExempt()) {
			cir.setReturnValue(false);
		}
	}

	/**
	 * Never counts players or their vehicles as floating, so they are not kicked for flying. Vanilla resets its
	 * counters whenever the flag reads false, so a player who stops being exempt starts from zero.
	 */
	@ModifyExpressionValue(
			method = "tickPlayer",
			at = {
				@At(
						value = "FIELD",
						target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;clientIsFloating:Z",
						opcode = Opcodes.GETFIELD),
				@At(
						value = "FIELD",
						target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;clientVehicleIsFloating:Z",
						opcode = Opcodes.GETFIELD)
			},
			require = 2)
	private boolean nomovementcheck$neverFloating(final boolean original) {
		return original && !this.nomovementcheck$isExempt();
	}

	/**
	 * Accepts more than one position packet per client tick instead of kicking for the second one.
	 */
	@ModifyExpressionValue(
			method = "handleMovePlayer",
			at = @At(
					value = "FIELD",
					target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;receivedPositionThisTick:Z",
					opcode = Opcodes.GETFIELD))
	private boolean nomovementcheck$allowSeveralPositionsPerTick(final boolean original) {
		return original && !this.nomovementcheck$isExempt();
	}

	/**
	 * Drops movement packets that cannot be applied (non-finite values, or a teleport confirmation the server was
	 * not waiting for) instead of kicking the player. Vanilla returns right after each of these calls.
	 */
	@WrapOperation(
			method = {"handleMovePlayer", "handleMoveVehicle", "handleAcceptTeleportPacket"},
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;disconnect(Lnet/minecraft/network/chat/Component;)V"),
			require = 4)
	private void nomovementcheck$dropInsteadOfKicking(
			final ServerGamePacketListenerImpl listener, final Component reason, final Operation<Void> original) {
		if (!this.nomovementcheck$isExempt()) {
			original.call(listener, reason);
		}
	}

	/**
	 * Applies the position of a sleeping player like any other, instead of ignoring it and setting the player back
	 * to the bed. Falling asleep is announced with a teleport, so this only sees positions the client sent after it
	 * knew about the bed.
	 */
	@ModifyExpressionValue(
			method = "handlePlayerPositionChange",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;isSleeping()Z", ordinal = 0))
	private boolean nomovementcheck$applyPositionWhileSleeping(final boolean original) {
		return original && !this.nomovementcheck$isExempt();
	}

	/**
	 * Moves a vehicle the player rides but does not control to the position the client reported, instead of
	 * resetting the client's vehicle. Runs after vanilla has validated the packet and checked that no teleport is
	 * pending; vehicles the player controls continue into the vanilla code.
	 */
	@Inject(
			method = "handleMoveVehicle",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;getRootVehicle()Lnet/minecraft/world/entity/Entity;"),
			cancellable = true)
	private void nomovementcheck$moveUncontrolledVehicle(final ServerboundMoveVehiclePacket packet, final CallbackInfo ci) {
		if (!this.nomovementcheck$isExempt()) {
			return;
		}

		Entity vehicle = this.player.getRootVehicle();
		if (vehicle == this.player || vehicle.getControllingPassenger() == this.player) {
			return;
		}

		PositionAndRotation movingTo = packet.movingTo();
		Vec3 position = movingTo.position();
		vehicle.absSnapTo(
				clampHorizontal(position.x()),
				clampVertical(position.y()),
				clampHorizontal(position.z()),
				Mth.wrapDegrees(movingTo.yRot()),
				Mth.wrapDegrees(movingTo.xRot()));
		this.player.level().getChunkSource().move(this.player);
		ci.cancel();
	}

	/**
	 * Applies far moves of players as teleports. See {@link NoMovementCheck#MAX_SIMULATED_DISTANCE_SQR}.
	 */
	@WrapOperation(
			method = "handlePlayerPositionChange",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/server/level/ServerPlayer;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V"))
	private void nomovementcheck$teleportFarPlayerMoves(
			final ServerPlayer mover, final MoverType type, final Vec3 delta, final Operation<Void> original) {
		if (!this.nomovementcheck$isTeleport(delta)) {
			original.call(mover, type, delta);
		}
	}

	/**
	 * Applies far moves of vehicles as teleports. See {@link NoMovementCheck#MAX_SIMULATED_DISTANCE_SQR}.
	 */
	@WrapOperation(
			method = "handleMoveVehicle",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/world/entity/Entity;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V"))
	private void nomovementcheck$teleportFarVehicleMoves(
			final Entity mover, final MoverType type, final Vec3 delta, final Operation<Void> original) {
		if (!this.nomovementcheck$isTeleport(delta)) {
			original.call(mover, type, delta);
		}
	}

	@Unique
	private boolean nomovementcheck$isTeleport(final Vec3 delta) {
		return this.nomovementcheck$isExempt() && delta.lengthSqr() > NoMovementCheck.MAX_SIMULATED_DISTANCE_SQR;
	}
}
