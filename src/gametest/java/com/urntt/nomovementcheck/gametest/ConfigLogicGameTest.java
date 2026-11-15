package com.urntt.nomovementcheck.gametest;

import static com.urntt.nomovementcheck.gametest.TestSupport.LOGGER;
import static com.urntt.nomovementcheck.gametest.TestSupport.check;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.urntt.nomovementcheck.NoMovementCheck;
import com.urntt.nomovementcheck.config.NoMovementCheckConfig;
import com.urntt.nomovementcheck.config.NoMovementCheckConfig.PlayerOverride;
import com.urntt.nomovementcheck.config.NoMovementCheckConfig.PlayerRef;
import com.urntt.nomovementcheck.text.Texts;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * Checks the rules that need no world: the configuration defaults, who the mod applies to, the saved file, and the
 * translations. It works on its own temporary config files, so it does not touch the game's.
 */
@SuppressWarnings("UnstableApiUsage")
public final class ConfigLogicGameTest implements FabricClientGameTest {
	private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-000000000002");

	@Override
	public void runTest(final ClientGameTestContext context) {
		try {
			Path directory = Files.createTempDirectory("nomovementcheck-test");
			checkDefaults(directory.resolve("defaults.json"));
			checkWhoIsExempt(directory.resolve("rules.json"));
			checkSavedFile(directory.resolve("saved.json"));
			checkIncompleteAndBrokenFiles(directory);
			checkChangeListener(directory.resolve("listener.json"));
			checkTranslations();
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		LOGGER.info("Config logic checks passed");
	}

	private static void checkDefaults(final Path path) {
		NoMovementCheckConfig config = NoMovementCheckConfig.load(path);
		check(!config.isEnabled(), "the mod should be disabled by default");
		check(config.appliesByDefault(), "the mod should apply to players without their own setting by default");
		check(config.overrides().isEmpty(), "no player should have their own setting by default");
		check(Files.exists(path), "a missing config file should be created");
		check(!config.isExempt(ALEX), "nobody should be exempt while the mod is disabled");
	}

	private static void checkWhoIsExempt(final Path path) {
		NoMovementCheckConfig config = NoMovementCheckConfig.load(path);
		config.setEnabled(true);
		check(config.isExempt(ALEX), "enabled with the default on: players without their own setting should be exempt");

		check(config.setOverrides(List.of(new PlayerRef(ALEX, "Alex")), false) == 1, "a new setting should count as a change");
		check(!config.isExempt(ALEX), "a player's own setting should win over the default");
		check(config.isExempt(STEVE), "other players should keep following the default");
		check(config.setOverrides(List.of(new PlayerRef(ALEX, "Alex")), false) == 0, "repeating a setting should not count as a change");

		config.setAppliesByDefault(false);
		check(!config.isExempt(STEVE), "enabled with the default off: players without their own setting should be checked");
		config.setOverrides(List.of(new PlayerRef(STEVE, "Steve")), true);
		check(config.isExempt(STEVE), "a player set to true should be exempt even with the default off");

		config.setEnabled(false);
		check(!config.isExempt(STEVE), "nobody should be exempt while the mod is disabled");
		check(config.appliesTo(STEVE), "the player's setting should be kept while the mod is disabled");

		config.setEnabled(true);
		check(config.removeOverrides(List.of(STEVE, UUID.randomUUID())) == 1, "only existing settings should count as removed");
		check(!config.isExempt(STEVE), "a player without their own setting should follow the default again");
		check(config.clearOverrides() == 1, "clearing should report how many settings were removed");
		check(config.overrides().isEmpty(), "clearing should remove every player's own setting");
	}

	private static void checkSavedFile(final Path path) throws IOException {
		NoMovementCheckConfig config = NoMovementCheckConfig.load(path);
		config.setEnabled(true);
		config.setAppliesByDefault(false);
		config.setOverrides(List.of(new PlayerRef(STEVE, "Steve"), new PlayerRef(ALEX, "Alex")), true);
		config.setOverrides(List.of(new PlayerRef(STEVE, "Steve")), false);

		NoMovementCheckConfig reloaded = NoMovementCheckConfig.load(path);
		check(reloaded.isEnabled(), "the enabled state should be saved");
		check(!reloaded.appliesByDefault(), "the default should be saved");
		check(reloaded.overrides().equals(List.of(new PlayerOverride(ALEX, "Alex", true), new PlayerOverride(STEVE, "Steve", false))),
				"the players' own settings should be saved and listed by name, got " + reloaded.overrides());

		Map<String, Object> json = new Gson().fromJson(Files.readString(path), new TypeToken<Map<String, Object>>() { }.getType());
		check(json.keySet().equals(Set.of("enabled", "default", "players")),
				"the config file should have the documented keys, got " + json.keySet());
	}

	private static void checkIncompleteAndBrokenFiles(final Path directory) throws IOException {
		Path incomplete = directory.resolve("incomplete.json");
		Files.writeString(incomplete, "{\"enabled\": true}");
		NoMovementCheckConfig fromIncomplete = NoMovementCheckConfig.load(incomplete);
		check(fromIncomplete.isEnabled(), "keys present in the file should be read");
		check(fromIncomplete.appliesByDefault(), "keys missing from the file should take their default");
		check(fromIncomplete.overrides().isEmpty(), "a missing player list should be empty");

		Path broken = directory.resolve("broken.json");
		String garbage = "{ this is not json";
		Files.writeString(broken, garbage);
		NoMovementCheckConfig fromBroken = NoMovementCheckConfig.load(broken);
		check(!fromBroken.isEnabled() && fromBroken.appliesByDefault(), "an unreadable file should fall back to the defaults");
		check(Files.readString(broken).equals(garbage), "an unreadable file should be left untouched until something changes");
		fromBroken.setEnabled(true);
		check(NoMovementCheckConfig.load(broken).isEnabled(), "the next change should replace an unreadable file");
	}

	private static void checkChangeListener(final Path path) {
		NoMovementCheckConfig config = NoMovementCheckConfig.load(path);
		AtomicInteger changes = new AtomicInteger();
		config.setChangeListener(changes::incrementAndGet);

		config.setEnabled(false);
		config.setAppliesByDefault(true);
		config.removeOverrides(List.of(ALEX));
		config.clearOverrides();
		check(changes.get() == 0, "calls that change nothing should not be reported");

		config.setEnabled(true);
		config.setOverrides(List.of(new PlayerRef(ALEX, "Alex"), new PlayerRef(STEVE, "Steve")), true);
		check(changes.get() == 2, "each change should be reported once, got " + changes.get());
	}

	/**
	 * Both languages must have the same keys, and text sent by the server must carry its English fallback for
	 * clients without the mod.
	 */
	private static void checkTranslations() throws IOException {
		Map<String, String> english = readLanguage("en_us");
		Map<String, String> chinese = readLanguage("zh_cn");
		check(english.keySet().equals(chinese.keySet()), "en_us and zh_cn should have the same keys");

		String key = "commands.nomovementcheck.enabled.set.true";
		Component component = Texts.translatable(key);
		Optional<String> fallback = component.getContents() instanceof TranslatableContents contents
				? Optional.ofNullable(contents.getFallback())
				: Optional.empty();
		check(fallback.equals(Optional.of(english.get(key))), "server text should fall back to the English text, got " + fallback);

		boolean rejected = false;
		try {
			Texts.translatable("commands.nomovementcheck.no_such_key");
		} catch (IllegalArgumentException e) {
			rejected = true;
		}
		check(rejected, "a translation key without English text should be rejected");
	}

	private static Map<String, String> readLanguage(final String code) throws IOException {
		String path = "/assets/" + NoMovementCheck.MOD_ID + "/lang/" + code + ".json";
		try (InputStream stream = NoMovementCheck.class.getResourceAsStream(path)) {
			check(stream != null, "missing language file " + path);
			try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
				return new Gson().fromJson(reader, new TypeToken<Map<String, String>>() { }.getType());
			}
		}
	}
}
