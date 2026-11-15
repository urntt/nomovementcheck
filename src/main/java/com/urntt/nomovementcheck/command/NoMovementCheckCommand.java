package com.urntt.nomovementcheck.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.urntt.nomovementcheck.NoMovementCheck;
import com.urntt.nomovementcheck.config.NoMovementCheckConfig;
import com.urntt.nomovementcheck.config.NoMovementCheckConfig.PlayerOverride;
import com.urntt.nomovementcheck.config.NoMovementCheckConfig.PlayerRef;
import com.urntt.nomovementcheck.text.Texts;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.server.players.NameAndId;

/**
 * The {@code /nomovementcheck} command. It needs {@link NoMovementCheck#REQUIRED_PERMISSION}.
 */
public final class NoMovementCheckCommand {
	private static final String VALUE = "value";
	private static final String TARGETS = "targets";

	private NoMovementCheckCommand() {
	}

	public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal(NoMovementCheck.MOD_ID)
				.requires(Commands.hasPermission(NoMovementCheck.REQUIRED_PERMISSION))
				.executes(context -> showStatus(context.getSource()))
				.then(Commands.literal("enabled")
						.executes(context -> queryEnabled(context.getSource()))
						.then(Commands.argument(VALUE, BoolArgumentType.bool())
								.executes(context -> setEnabled(context.getSource(), BoolArgumentType.getBool(context, VALUE)))))
				.then(Commands.literal("default")
						.executes(context -> queryDefault(context.getSource()))
						.then(Commands.argument(VALUE, BoolArgumentType.bool())
								.executes(context -> setDefault(context.getSource(), BoolArgumentType.getBool(context, VALUE)))))
				.then(Commands.literal("player")
						// The literals win over a player who happens to be called "list" or "clear"; a selector
						// such as @a[name=list] still reaches that player.
						.then(Commands.literal("list").executes(context -> listPlayers(context.getSource())))
						.then(Commands.literal("clear").executes(context -> clearPlayers(context.getSource())))
						.then(Commands.argument(TARGETS, GameProfileArgument.gameProfile())
								.executes(context -> queryPlayers(
										context.getSource(), GameProfileArgument.getGameProfiles(context, TARGETS)))
								.then(Commands.literal("true").executes(context -> setPlayers(
										context.getSource(), GameProfileArgument.getGameProfiles(context, TARGETS), true)))
								.then(Commands.literal("false").executes(context -> setPlayers(
										context.getSource(), GameProfileArgument.getGameProfiles(context, TARGETS), false)))
								.then(Commands.literal("reset").executes(context -> resetPlayers(
										context.getSource(), GameProfileArgument.getGameProfiles(context, TARGETS)))))));
	}

	private static int showStatus(final CommandSourceStack source) {
		NoMovementCheckConfig config = NoMovementCheck.config();
		int overrides = config.overrides().size();
		source.sendSuccess(() -> Texts.translatable("commands.nomovementcheck.enabled.query." + config.isEnabled()), false);
		source.sendSuccess(() -> Texts.translatable("commands.nomovementcheck.default.query." + config.appliesByDefault()), false);
		source.sendSuccess(() -> Texts.translatable("commands.nomovementcheck.status.overrides", overrides), false);
		return 1;
	}

	private static int queryEnabled(final CommandSourceStack source) {
		boolean enabled = NoMovementCheck.config().isEnabled();
		source.sendSuccess(() -> Texts.translatable("commands.nomovementcheck.enabled.query." + enabled), false);
		return enabled ? 1 : 0;
	}

	private static int setEnabled(final CommandSourceStack source, final boolean enabled) {
		if (!NoMovementCheck.config().setEnabled(enabled)) {
			source.sendFailure(Texts.translatable("commands.nomovementcheck.enabled.unchanged." + enabled));
			return 0;
		}

		source.sendSuccess(() -> Texts.translatable("commands.nomovementcheck.enabled.set." + enabled), true);
		return 1;
	}

	private static int queryDefault(final CommandSourceStack source) {
		boolean appliesByDefault = NoMovementCheck.config().appliesByDefault();
		source.sendSuccess(() -> Texts.translatable("commands.nomovementcheck.default.query." + appliesByDefault), false);
		return appliesByDefault ? 1 : 0;
	}

	private static int setDefault(final CommandSourceStack source, final boolean appliesByDefault) {
		if (!NoMovementCheck.config().setAppliesByDefault(appliesByDefault)) {
			source.sendFailure(Texts.translatable("commands.nomovementcheck.default.unchanged." + appliesByDefault));
			return 0;
		}

		source.sendSuccess(() -> Texts.translatable("commands.nomovementcheck.default.set." + appliesByDefault), true);
		return 1;
	}

	private static int queryPlayers(final CommandSourceStack source, final Collection<NameAndId> targets) {
		NoMovementCheckConfig config = NoMovementCheck.config();
		for (NameAndId target : targets) {
			Optional<PlayerOverride> override = config.override(target.id());
			String origin = override.isPresent() ? "own" : "default";
			boolean value = config.appliesTo(target.id());
			source.sendSuccess(
					() -> Texts.translatable("commands.nomovementcheck.player.query." + origin + "." + value, target.name()), false);
		}

		if (!config.isEnabled()) {
			source.sendSuccess(() -> Texts.translatable("commands.nomovementcheck.enabled.query.false"), false);
		}
		return targets.size();
	}

	private static int setPlayers(final CommandSourceStack source, final Collection<NameAndId> targets, final boolean value) {
		List<PlayerRef> players = targets.stream().map(target -> new PlayerRef(target.id(), target.name())).toList();
		int changed = NoMovementCheck.config().setOverrides(players, value);
		if (changed == 0) {
			source.sendFailure(Texts.translatable("commands.nomovementcheck.player.set.unchanged"));
			return 0;
		}

		source.sendSuccess(() -> countedMessage("commands.nomovementcheck.player.set." + value, targets, changed), true);
		return changed;
	}

	private static int resetPlayers(final CommandSourceStack source, final Collection<NameAndId> targets) {
		int changed = NoMovementCheck.config().removeOverrides(targets.stream().map(NameAndId::id).toList());
		if (changed == 0) {
			source.sendFailure(Texts.translatable("commands.nomovementcheck.player.reset.unchanged"));
			return 0;
		}

		source.sendSuccess(() -> countedMessage("commands.nomovementcheck.player.reset", targets, changed), true);
		return changed;
	}

	private static int listPlayers(final CommandSourceStack source) {
		List<PlayerOverride> overrides = NoMovementCheck.config().overrides();
		if (overrides.isEmpty()) {
			source.sendSuccess(() -> Texts.translatable("commands.nomovementcheck.player.list.empty"), false);
			return 0;
		}

		Component names = ComponentUtils.formatList(
				overrides, override -> Texts.translatable("commands.nomovementcheck.player.list.entry." + override.value(), override.name()));
		source.sendSuccess(() -> Texts.translatable("commands.nomovementcheck.player.list", overrides.size(), names), false);
		return overrides.size();
	}

	private static int clearPlayers(final CommandSourceStack source) {
		int removed = NoMovementCheck.config().clearOverrides();
		if (removed == 0) {
			source.sendFailure(Texts.translatable("commands.nomovementcheck.player.list.empty"));
			return 0;
		}

		source.sendSuccess(() -> Texts.translatable("commands.nomovementcheck.player.clear", removed), true);
		return removed;
	}

	/**
	 * Names the player when the command targeted exactly one, and gives the number of changed players otherwise.
	 */
	private static Component countedMessage(final String key, final Collection<NameAndId> targets, final int changed) {
		return targets.size() == 1
				? Texts.translatable(key + ".single", targets.iterator().next().name())
				: Texts.translatable(key + ".multiple", changed);
	}
}
