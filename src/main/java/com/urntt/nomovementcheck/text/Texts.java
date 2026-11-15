package com.urntt.nomovementcheck.text;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.urntt.nomovementcheck.NoMovementCheck;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Builds the text the server sends to players.
 *
 * <p>Clients are not required to install the mod, so they may not have its translations. Every component
 * therefore carries the English text as its fallback. The fallback comes from the bundled {@code en_us.json}, which
 * stays the only place the English text is written down.
 */
public final class Texts {
	private static final String ENGLISH_PATH = "/assets/" + NoMovementCheck.MOD_ID + "/lang/en_us.json";
	private static final Map<String, String> ENGLISH = loadEnglish();

	private Texts() {
	}

	/**
	 * Returns a translatable component that falls back to English on clients without the mod.
	 *
	 * @throws IllegalArgumentException if the key has no English text
	 */
	public static MutableComponent translatable(final String key, final Object... args) {
		String english = ENGLISH.get(key);
		if (english == null) {
			throw new IllegalArgumentException("No English text for translation key " + key);
		}
		return Component.translatableWithFallback(key, english, args);
	}

	private static Map<String, String> loadEnglish() {
		try (InputStream stream = Texts.class.getResourceAsStream(ENGLISH_PATH)) {
			if (stream == null) {
				throw new IllegalStateException("Missing language file " + ENGLISH_PATH);
			}

			try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
				return new Gson().fromJson(reader, new TypeToken<Map<String, String>>() { }.getType());
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
