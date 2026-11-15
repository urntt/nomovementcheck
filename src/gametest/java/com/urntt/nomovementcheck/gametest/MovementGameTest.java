package com.urntt.nomovementcheck.gametest;

import static com.urntt.nomovementcheck.gametest.TestSupport.LOGGER;
import static com.urntt.nomovementcheck.gametest.TestSupport.check;

import com.urntt.nomovementcheck.NoMovementCheck;
import com.urntt.nomovementcheck.config.NoMovementCheckConfig;
import java.util.function.Consumer;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.PositionAndRotation;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Checks, on a dedicated server, that every movement check the mod removes stops affecting exempt players, and
 * still affects players that are not exempt.
 *
 * <p>The client cheats the way a modified client would: it places itself or its vehicle somewhere vanilla would
 * not allow, or sends packets vanilla would not send. Each scenario runs twice. Without the exemption the server
 * must react as vanilla does, with a setback or a kick, which also shows that the scenario really triggers the
 * check. With the exemption the server must accept the client's position and keep the connection.
 */
@SuppressWarnings("UnstableApiUsage")
public final class MovementGameTest implements FabricClientGameTest {
	/** How close two positions must be, in blocks, to count as the same place. */
	private static final double TOLERANCE = 0.5;
	/** Boats slide a little under the client's own physics. */
	private static final double VEHICLE_TOLERANCE = 1.0;
	/** Vanilla kicks a floating player after 80 ticks. */
	private static final int KICK_TIMEOUT_TICKS = 200;
	private static final int HOVER_TICKS = 140;
	/** The distance per axis of a move that is far too long to simulate along its path. */
	private static final double FAR = 30_000.0;
	/** Handling a far move takes milliseconds when it is applied as a teleport, and much longer when simulated. */
	private static final long MAX_FAR_MOVE_MILLIS = 5_000;
	private static final String TEST_ENTITY_TAG = "nomovementcheck_test";

	private ClientGameTestContext context;
	private TestDedicatedServerContext server;
	private @Nullable TestDedicatedServerConnection connection;
	private String playerName;
	/** The block the player stands in at the start of each scenario, and the bottom center of it. */
	private BlockPos base;
	private Vec3 start;
	/** Where the boat scenarios place their boat: beside the wall, away from the player's start. */
	private Vec3 boatStart;

	@Override
	public void runTest(final ClientGameTestContext context) {
		this.context = context;
		try (TestDedicatedServerContext server = context.worldBuilder().createServer()) {
			this.server = server;
			try {
				this.connect();
				this.prepareWorld();

				this.checkPlayerMove("moving too quickly", this.start.add(-50.0, 0.0, 0.0));
				this.checkPlayerMove("moving through a wall", this.start.add(3.0, 0.0, 0.0));
				this.checkPlayerMove("moving into a block", this.start.add(1.45, 0.0, 0.0));
				this.checkMoveWhileSleeping();
				this.checkUncontrolledVehicle();
				this.checkVehicleMove("vehicle moving too quickly", new Vec3(-30.0, 0.0, 0.0));
				this.checkVehicleMove("vehicle moving through a wall", new Vec3(4.0, 0.0, 0.0));
				this.checkFarMoves();
				this.checkHovering();
				this.checkHoveringInVehicle();
				this.checkPackets("two position packets in one tick", MovementGameTest::sendTwoPositions);
				this.checkPackets("a position that is not a number", MovementGameTest::sendNotANumber);
				this.checkPackets("a vehicle position that is not a number", MovementGameTest::sendVehicleNotANumber);
				this.checkPackets("an unexpected teleport confirmation", MovementGameTest::sendTeleportConfirmations);
			} finally {
				this.disconnect();
			}
		}
	}

	// Scenarios

	/**
	 * Places the client player at {@code target} and lets the client report that position by itself.
	 */
	private void checkPlayerMove(final String scenario, final Vec3 target) {
		for (boolean exempt : new boolean[] {false, true}) {
			this.beginScenario(exempt);
			this.context.runOnClient(client -> client.player.setPos(target));
			this.settle();

			Vec3 clientPos = this.clientPosition();
			Vec3 serverPos = this.serverPosition();
			if (exempt) {
				LOGGER.info("{}, exempt: client {} from the target, server {} from the client",
						scenario, distance(clientPos, target), distance(serverPos, clientPos));
				check(clientPos.distanceTo(target) < TOLERANCE, scenario + ": an exempt player should not be set back");
				check(serverPos.distanceTo(clientPos) < TOLERANCE, scenario + ": the server should adopt an exempt player's position");
			} else {
				LOGGER.info("{}, not exempt: client {} from the start, server {} from the start",
						scenario, distance(clientPos, this.start), distance(serverPos, this.start));
				check(clientPos.distanceTo(this.start) < TOLERANCE, scenario + ": a player that is not exempt should be set back");
				check(serverPos.distanceTo(this.start) < TOLERANCE, scenario + ": the server should reject the position of a player that is not exempt");
			}
		}
	}

	/**
	 * A sleeping player who moves away from the bed is set back to it by vanilla.
	 */
	private void checkMoveWhileSleeping() {
		String scenario = "moving while asleep";
		BlockPos bedFoot = this.base.offset(0, 0, -4);
		BlockPos bedHead = this.base.offset(0, 0, -5);
		this.server.runCommand("time set 18000");
		this.server.runCommand(setBlock(bedFoot, "minecraft:red_bed[part=foot,facing=north]"));
		this.server.runCommand(setBlock(bedHead, "minecraft:red_bed[part=head,facing=north]"));
		Vec3 target = Vec3.atBottomCenterOf(bedHead).add(3.0, 0.0, 0.0);

		for (boolean exempt : new boolean[] {false, true}) {
			this.beginScenario(exempt);
			this.server.runOnServer(server -> check(this.connection.getServerPlayer().startSleeping(bedHead), "the player should fall asleep"));
			this.context.waitFor(client -> client.player.isSleeping());
			this.settle();
			Vec3 bed = this.serverPosition();
			check(this.clientPosition().distanceTo(bed) < TOLERANCE, scenario + ": the client should lie in the bed");

			this.context.runOnClient(client -> client.player.setPos(target));
			this.settle();

			Vec3 clientPos = this.clientPosition();
			Vec3 serverPos = this.serverPosition();
			if (exempt) {
				LOGGER.info("{}, exempt: client {} from the target, server {} from the client",
						scenario, distance(clientPos, target), distance(serverPos, clientPos));
				check(clientPos.distanceTo(target) < TOLERANCE, scenario + ": an exempt player should not be set back to the bed");
				check(serverPos.distanceTo(clientPos) < TOLERANCE, scenario + ": the server should adopt an exempt sleeper's position");
			} else {
				LOGGER.info("{}, not exempt: client {} from the bed, server {} from the bed",
						scenario, distance(clientPos, bed), distance(serverPos, bed));
				check(clientPos.distanceTo(bed) < TOLERANCE, scenario + ": a player that is not exempt should be set back to the bed");
				check(serverPos.distanceTo(bed) < TOLERANCE, scenario + ": the server should keep a player that is not exempt in the bed");
			}

			this.server.runOnServer(server -> this.connection.getServerPlayer().stopSleepInBed(true, true));
			this.context.waitFor(client -> !client.player.isSleeping());
		}

		this.server.runCommand(setBlock(bedHead, "minecraft:air"));
		this.server.runCommand("time set 6000");
	}

	/**
	 * A passenger that does not control its vehicle sends a vehicle move anyway. Vanilla ignores it and resets the
	 * client's vehicle.
	 */
	private void checkUncontrolledVehicle() {
		String scenario = "moving a vehicle the player does not control";
		Vec3 vehicleStart = this.start.add(0.0, 0.0, 4.0);
		Vec3 target = vehicleStart.add(5.0, 0.0, 0.0);

		for (boolean exempt : new boolean[] {false, true}) {
			this.beginScenario(exempt);
			this.mount("minecraft:pig", vehicleStart, "NoAI:1b,");
			this.context.runOnClient(client -> client.getConnection().send(
					new ServerboundMoveVehiclePacket(PositionAndRotation.of(target, 0.0F, 0.0F), true)));
			this.settle();

			Vec3 serverPos = this.serverVehiclePosition();
			if (exempt) {
				LOGGER.info("{}, exempt: server vehicle {} from the target", scenario, distance(serverPos, target));
				check(serverPos.distanceTo(target) < TOLERANCE, scenario + ": the server should move an exempt player's vehicle");
			} else {
				LOGGER.info("{}, not exempt: server vehicle {} from its start", scenario, distance(serverPos, vehicleStart));
				check(serverPos.distanceTo(vehicleStart) < TOLERANCE, scenario + ": the server should not move the vehicle of a player that is not exempt");
			}
			this.dismount();
		}
	}

	/**
	 * Places the boat the player steers at an offset from where it was and lets the client report it.
	 */
	private void checkVehicleMove(final String scenario, final Vec3 offset) {
		Vec3 target = this.boatStart.add(offset);

		for (boolean exempt : new boolean[] {false, true}) {
			this.beginScenario(exempt);
			this.mountBoat(this.boatStart);

			this.context.runOnClient(client -> client.player.getRootVehicle().setPos(target));
			this.settle();

			Vec3 clientPos = this.context.computeOnClient(client -> client.player.getRootVehicle().position());
			Vec3 serverPos = this.serverVehiclePosition();
			if (exempt) {
				LOGGER.info("{}, exempt: client vehicle {} from the target, server vehicle {} from the client's",
						scenario, distance(clientPos, target), distance(serverPos, clientPos));
				check(clientPos.distanceTo(target) < VEHICLE_TOLERANCE, scenario + ": an exempt player's vehicle should not be reset");
				check(serverPos.distanceTo(clientPos) < VEHICLE_TOLERANCE, scenario + ": the server should adopt the position of an exempt player's vehicle");
			} else {
				LOGGER.info("{}, not exempt: client vehicle {} from its start, server vehicle {} from its start",
						scenario, distance(clientPos, this.boatStart), distance(serverPos, this.boatStart));
				check(clientPos.distanceTo(this.boatStart) < VEHICLE_TOLERANCE, scenario + ": the vehicle of a player that is not exempt should be reset");
				check(serverPos.distanceTo(this.boatStart) < VEHICLE_TOLERANCE, scenario + ": the server should reject the vehicle position of a player that is not exempt");
			}
			this.dismount();
		}
	}

	/**
	 * A single packet that crosses a huge distance must be applied without simulating the path, which would stall
	 * the server. Only exempt players can get this far: vanilla's speed check stops everyone else.
	 */
	private void checkFarMoves() {
		Vec3 offset = new Vec3(FAR, 0.0, FAR);

		this.beginScenario(true);
		Vec3 target = this.start.add(offset);
		long millis = this.timeUntilServerHandled(client -> client.player.setPos(target));
		Vec3 serverPos = this.serverPosition();
		LOGGER.info("far move, exempt: handled in {} ms, server {} from the target", millis, distance(serverPos, target));
		// The client sinks slowly while the chunks around it are not loaded yet.
		check(serverPos.distanceTo(target) < 5.0, "far move: the server should adopt the position of an exempt player");
		check(millis < MAX_FAR_MOVE_MILLIS, "far move: took " + millis + " ms, so the server simulated the whole path");

		this.beginScenario(true);
		Vec3 vehicleTarget = this.boatStart.add(offset);
		this.mountBoat(this.boatStart);
		long vehicleMillis = this.timeUntilServerHandled(client -> client.player.getRootVehicle().setPos(vehicleTarget));
		Vec3 vehicleServerPos = this.serverVehiclePosition();
		LOGGER.info("far vehicle move, exempt: handled in {} ms, server vehicle {} from the target",
				vehicleMillis, distance(vehicleServerPos, vehicleTarget));
		check(vehicleServerPos.distanceTo(vehicleTarget) < 5.0, "far vehicle move: the server should adopt the vehicle position of an exempt player");
		check(vehicleMillis < MAX_FAR_MOVE_MILLIS, "far vehicle move: took " + vehicleMillis + " ms, so the server simulated the whole path");
		this.dismount();
	}

	/**
	 * The client hovers in the air without being allowed to fly. Vanilla kicks it for flying.
	 */
	private void checkHovering() {
		String scenario = "hovering";
		Vec3 target = this.start.add(0.0, 5.0, 0.0);
		Consumer<Minecraft> hover = client -> {
			client.player.setPos(target);
			client.player.setDeltaMovement(Vec3.ZERO);
			client.player.getAbilities().flying = true;
		};

		this.beginScenario(false);
		this.context.runOnClient(hover::accept);
		this.expectKick(scenario);

		this.beginScenario(true);
		this.context.runOnClient(hover::accept);
		this.context.waitTicks(HOVER_TICKS);
		this.settle();
		check(this.isConnected(), scenario + ": an exempt player should not be kicked for flying");
		Vec3 clientPos = this.clientPosition();
		Vec3 serverPos = this.serverPosition();
		LOGGER.info("{}, exempt: still connected after {} ticks, client {} from the target, server {} from the client",
				scenario, HOVER_TICKS, distance(clientPos, target), distance(serverPos, clientPos));
		check(clientPos.distanceTo(target) < TOLERANCE, scenario + ": an exempt player should stay where it hovers");
		check(serverPos.distanceTo(clientPos) < TOLERANCE, scenario + ": the server should adopt an exempt player's position");
	}

	/**
	 * The boat the player steers hovers in the air. Vanilla kicks the player for flying.
	 */
	private void checkHoveringInVehicle() {
		String scenario = "hovering in a vehicle";
		Vec3 target = this.boatStart.add(0.0, 5.0, 0.0);
		Consumer<Minecraft> hover = client -> {
			Entity vehicle = client.player.getRootVehicle();
			vehicle.setNoGravity(true);
			vehicle.setPos(target);
			vehicle.setDeltaMovement(Vec3.ZERO);
		};

		this.beginScenario(false);
		this.mountBoat(this.boatStart);
		this.context.runOnClient(hover::accept);
		this.expectKick(scenario);

		this.beginScenario(true);
		this.mountBoat(this.boatStart);
		this.context.runOnClient(hover::accept);
		this.context.waitTicks(HOVER_TICKS);
		this.settle();
		check(this.isConnected(), scenario + ": an exempt player should not be kicked for flying");
		Vec3 clientPos = this.context.computeOnClient(client -> client.player.getRootVehicle().position());
		Vec3 serverPos = this.serverVehiclePosition();
		LOGGER.info("{}, exempt: still connected after {} ticks, client vehicle {} from the target, server vehicle {} from the client's",
				scenario, HOVER_TICKS, distance(clientPos, target), distance(serverPos, clientPos));
		check(clientPos.distanceTo(target) < VEHICLE_TOLERANCE, scenario + ": an exempt player's vehicle should stay where it hovers");
		check(serverPos.distanceTo(clientPos) < VEHICLE_TOLERANCE, scenario + ": the server should adopt the position of an exempt player's vehicle");
		this.dismount();
	}

	/**
	 * Sends packets that vanilla answers with a kick.
	 */
	private void checkPackets(final String scenario, final Consumer<Minecraft> sender) {
		this.beginScenario(false);
		this.context.runOnClient(sender::accept);
		this.expectKick(scenario);

		this.beginScenario(true);
		this.context.runOnClient(sender::accept);
		this.settle();
		this.context.waitTicks(20);
		check(this.isConnected(), scenario + ": an exempt player should not be kicked");
		check(this.serverPosition().distanceTo(this.start) < TOLERANCE, scenario + ": the packets should not move an exempt player");
		LOGGER.info("{}, exempt: still connected", scenario);
	}

	private static void sendTwoPositions(final Minecraft client) {
		Vec3 position = client.player.position();
		client.getConnection().send(new ServerboundMovePlayerPacket.Pos(position, true, false));
		client.getConnection().send(new ServerboundMovePlayerPacket.Pos(position, true, false));
	}

	private static void sendNotANumber(final Minecraft client) {
		Vec3 position = client.player.position();
		client.getConnection().send(new ServerboundMovePlayerPacket.Pos(Double.NaN, position.y, position.z, true, false));
	}

	private static void sendVehicleNotANumber(final Minecraft client) {
		Vec3 position = client.player.position();
		client.getConnection().send(new ServerboundMoveVehiclePacket(
				PositionAndRotation.of(new Vec3(Double.NaN, position.y, position.z), 0.0F, 0.0F), true));
	}

	/**
	 * Confirms teleports the server is not waiting for. The client does not know the id of the last teleport, so
	 * it tries every id the connection can have reached by now.
	 */
	private static void sendTeleportConfirmations(final Minecraft client) {
		Vec3 position = client.player.position();
		for (int id = 0; id < 200; id++) {
			client.getConnection().send(new ServerboundAcceptTeleportationPacket(id, position.x, position.y, position.z, 0.0F, 0.0F));
		}
	}

	// World and connection

	private void connect() {
		this.connection = this.server.connect();
		this.context.waitFor(client -> client.player != null);
		this.playerName = this.context.computeOnClient(client -> client.getGameProfile().name());
	}

	private void disconnect() {
		if (this.connection != null && this.isConnected()) {
			this.connection.close();
		}
		this.connection = null;
	}

	private boolean isConnected() {
		return this.context.computeOnClient(client -> client.level != null);
	}

	/**
	 * Chooses the place the scenarios start from and builds a wall next to it.
	 */
	private void prepareWorld() {
		this.server.runCommand("gamemode survival @a");
		this.server.runCommand("difficulty peaceful");
		this.server.runOnServer(server -> {
			// Start from known settings: the scenarios only switch the mod on and off.
			NoMovementCheckConfig config = NoMovementCheck.config();
			config.setEnabled(false);
			config.setAppliesByDefault(true);
			config.clearOverrides();
		});

		this.context.waitFor(client -> client.player.onGround());
		this.base = this.context.computeOnClient(client -> client.player.blockPosition());
		this.start = Vec3.atBottomCenterOf(this.base);
		this.boatStart = this.start.add(0.0, 0.0, 8.0);

		BlockPos wallFrom = this.base.offset(2, 0, -2);
		BlockPos wallTo = this.base.offset(2, 2, 10);
		this.server.runCommand("fill %d %d %d %d %d %d minecraft:stone".formatted(
				wallFrom.getX(), wallFrom.getY(), wallFrom.getZ(), wallTo.getX(), wallTo.getY(), wallTo.getZ()));
		this.context.waitFor(client -> !client.level.getBlockState(wallTo).isAir());
	}

	/**
	 * Switches the exemption and puts the player back at the start, on foot and on the ground.
	 */
	private void beginScenario(final boolean exempt) {
		this.server.runOnServer(server -> NoMovementCheck.config().setEnabled(exempt));
		// A player who was kicked while riding rejoins in the vehicle.
		this.server.runCommand("ride %s dismount".formatted(this.playerName));
		this.server.runCommand("kill @e[tag=%s]".formatted(TEST_ENTITY_TAG));
		// A client that is still flying from an earlier scenario would not land.
		this.context.runOnClient(client -> client.player.getAbilities().flying = false);
		this.server.runCommand("tp %s %s %s %s".formatted(this.playerName, this.start.x, this.start.y, this.start.z));
		this.context.waitFor(client -> client.player.position().distanceTo(this.start) < 0.1 && client.player.onGround());
		this.settle();
		check(this.serverPosition().distanceTo(this.start) < 0.1, "the player should be back at the start");
	}

	/**
	 * Waits until the client has reported its state, the server has handled it, and the client has handled the
	 * server's reaction. A setback is a round trip of its own, so the exchange is awaited twice.
	 */
	private void settle() {
		for (int round = 0; round < 2; round++) {
			this.context.waitTicks(3);
			this.connection.waitForServerboundPackets();
			this.connection.waitForClientboundPackets();
		}
		this.context.waitTicks(3);
	}

	/**
	 * Runs the action on the client and measures how long it takes until the server has handled what the client
	 * reported afterwards.
	 */
	private long timeUntilServerHandled(final Consumer<Minecraft> action) {
		long begin = System.nanoTime();
		this.context.runOnClient(action::accept);
		this.context.waitTicks(3);
		this.connection.waitForServerboundPackets();
		return (System.nanoTime() - begin) / 1_000_000L;
	}

	private void expectKick(final String scenario) {
		int ticks;
		try {
			ticks = this.context.waitFor(client -> client.level == null, KICK_TIMEOUT_TICKS);
		} catch (AssertionError e) {
			throw new AssertionError(scenario + ": a player that is not exempt should be kicked", e);
		}
		LOGGER.info("{}, not exempt: kicked after {} ticks", scenario, ticks);

		this.connection = null;
		this.context.setScreen(TitleScreen::new);
		this.connect();
	}

	private void mount(final String entityType, final Vec3 position, final String extraData) {
		this.server.runCommand("summon %s %s %s %s {%sTags:[\"%s\"]}".formatted(
				entityType, position.x, position.y, position.z, extraData, TEST_ENTITY_TAG));
		this.server.runCommand("ride %s mount @e[tag=%s,limit=1]".formatted(this.playerName, TEST_ENTITY_TAG));
		this.context.waitFor(client -> client.player.isPassenger());
		this.settle();
	}

	/**
	 * Puts the player into a boat, which the client then steers and reports to the server.
	 */
	private void mountBoat(final Vec3 position) {
		this.mount("minecraft:oak_boat", position, "");
		this.context.waitFor(client -> client.player.getRootVehicle().isLocalInstanceAuthoritative());
		this.settle();
	}

	private void dismount() {
		this.server.runCommand("ride %s dismount".formatted(this.playerName));
		this.server.runCommand("kill @e[tag=%s]".formatted(TEST_ENTITY_TAG));
		this.context.waitFor(client -> !client.player.isPassenger());
	}

	private Vec3 clientPosition() {
		return this.context.computeOnClient(client -> client.player.position());
	}

	private Vec3 serverPosition() {
		return this.onServer(server -> this.connection.getServerPlayer().position());
	}

	private Vec3 serverVehiclePosition() {
		return this.onServer(server -> this.connection.getServerPlayer().getRootVehicle().position());
	}

	private <T> T onServer(final Function<MinecraftServer, T> function) {
		return this.server.computeOnServer(function::apply);
	}

	private static String setBlock(final BlockPos pos, final String block) {
		return "setblock %d %d %d %s".formatted(pos.getX(), pos.getY(), pos.getZ(), block);
	}

	private static String distance(final Vec3 from, final Vec3 to) {
		return "%.2f".formatted(from.distanceTo(to));
	}
}
