package com.urntt.nomovementcheck.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;
import com.urntt.nomovementcheck.NoMovementCheck;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The server's settings, persisted as JSON. Every change is written to disk immediately and then reported to the
 * change listener.
 *
 * <p>The mod applies to a player when it is enabled and the player's own setting, or the default for players
 * without one, says so.
 */
public final class NoMovementCheckConfig {
	private static final Logger LOGGER = LoggerFactory.getLogger(NoMovementCheck.MOD_ID);
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Comparator<PlayerOverride> DISPLAY_ORDER = Comparator
			.comparing(PlayerOverride::name, String.CASE_INSENSITIVE_ORDER)
			.thenComparing(PlayerOverride::id);

	private final Path path;
	private final Map<UUID, PlayerOverride> overrides = new HashMap<>();
	private boolean enabled;
	private boolean appliesByDefault;
	private Runnable changeListener = () -> { };

	private NoMovementCheckConfig(final Path path, final Settings settings) {
		this.path = path;
		this.enabled = settings.enabled;
		this.appliesByDefault = settings.appliesByDefault;
		if (settings.players != null) {
			for (PlayerEntry entry : settings.players) {
				if (entry != null && entry.uuid != null) {
					String name = entry.name != null ? entry.name : entry.uuid.toString();
					this.overrides.put(entry.uuid, new PlayerOverride(entry.uuid, name, entry.value));
				}
			}
		}
	}

	/**
	 * Returns the location of the config file in the Fabric config directory.
	 */
	public static Path defaultPath() {
		return FabricLoader.getInstance().getConfigDir().resolve(NoMovementCheck.MOD_ID + ".json");
	}

	/**
	 * Loads the configuration from {@code path}. A missing file is created with the defaults. An unreadable
	 * file falls back to the defaults and is left untouched until the next change is saved.
	 */
	public static NoMovementCheckConfig load(final Path path) {
		if (Files.notExists(path)) {
			NoMovementCheckConfig config = new NoMovementCheckConfig(path, new Settings());
			config.save();
			return config;
		}

		try {
			Settings settings = GSON.fromJson(Files.readString(path), Settings.class);
			return new NoMovementCheckConfig(path, settings != null ? settings : new Settings());
		} catch (IOException | JsonParseException e) {
			LOGGER.warn("Failed to read config file {}, using defaults", path, e);
			return new NoMovementCheckConfig(path, new Settings());
		}
	}

	/**
	 * Sets the listener that is called after every change, once the change has been saved.
	 */
	public void setChangeListener(final Runnable changeListener) {
		this.changeListener = changeListener;
	}

	public boolean isEnabled() {
		return this.enabled;
	}

	/**
	 * Turns the whole mod on or off.
	 *
	 * @return whether the setting changed
	 */
	public boolean setEnabled(final boolean enabled) {
		if (this.enabled == enabled) {
			return false;
		}
		this.enabled = enabled;
		this.changed();
		return true;
	}

	/**
	 * Returns whether the mod applies to players that have no setting of their own.
	 */
	public boolean appliesByDefault() {
		return this.appliesByDefault;
	}

	/**
	 * Sets whether the mod applies to players that have no setting of their own.
	 *
	 * @return whether the setting changed
	 */
	public boolean setAppliesByDefault(final boolean appliesByDefault) {
		if (this.appliesByDefault == appliesByDefault) {
			return false;
		}
		this.appliesByDefault = appliesByDefault;
		this.changed();
		return true;
	}

	/**
	 * Returns the player's own setting, if the player has one.
	 */
	public Optional<PlayerOverride> override(final UUID id) {
		return Optional.ofNullable(this.overrides.get(id));
	}

	/**
	 * Returns the players that have a setting of their own, ordered by name.
	 */
	public List<PlayerOverride> overrides() {
		return this.overrides.values().stream().sorted(DISPLAY_ORDER).toList();
	}

	/**
	 * Returns what the settings say about the player, regardless of whether the mod is enabled.
	 */
	public boolean appliesTo(final UUID id) {
		PlayerOverride override = this.overrides.get(id);
		return override != null ? override.value() : this.appliesByDefault;
	}

	/**
	 * Returns whether the server currently trusts the player's movement.
	 */
	public boolean isExempt(final UUID id) {
		return this.enabled && this.appliesTo(id);
	}

	/**
	 * Gives each of the players a setting of their own.
	 *
	 * @return the number of players whose setting changed
	 */
	public int setOverrides(final Collection<PlayerRef> players, final boolean value) {
		int count = 0;
		for (PlayerRef player : players) {
			PlayerOverride previous = this.overrides.put(player.id(), new PlayerOverride(player.id(), player.name(), value));
			if (previous == null || previous.value() != value) {
				count++;
			}
		}
		if (count > 0) {
			this.changed();
		}
		return count;
	}

	/**
	 * Removes the players' own settings, so they follow the default again.
	 *
	 * @return the number of players that had a setting of their own
	 */
	public int removeOverrides(final Collection<UUID> ids) {
		int count = 0;
		for (UUID id : ids) {
			if (this.overrides.remove(id) != null) {
				count++;
			}
		}
		if (count > 0) {
			this.changed();
		}
		return count;
	}

	/**
	 * Removes every player's own setting.
	 *
	 * @return the number of settings removed
	 */
	public int clearOverrides() {
		int count = this.overrides.size();
		if (count > 0) {
			this.overrides.clear();
			this.changed();
		}
		return count;
	}

	private void changed() {
		this.save();
		this.changeListener.run();
	}

	private void save() {
		Settings settings = new Settings();
		settings.enabled = this.enabled;
		settings.appliesByDefault = this.appliesByDefault;
		for (PlayerOverride override : this.overrides()) {
			PlayerEntry entry = new PlayerEntry();
			entry.uuid = override.id();
			entry.name = override.name();
			entry.value = override.value();
			settings.players.add(entry);
		}

		try {
			Files.createDirectories(this.path.getParent());
			Files.writeString(this.path, GSON.toJson(settings));
		} catch (IOException e) {
			LOGGER.error("Failed to write config file {}", this.path, e);
		}
	}

	/**
	 * A player, identified by UUID. The name is only stored for display.
	 */
	public record PlayerRef(UUID id, String name) {
	}

	/**
	 * A player's own setting: whether the mod applies to them, whatever the default is.
	 */
	public record PlayerOverride(UUID id, String name, boolean value) {
	}

	/**
	 * The serialized form. Field initializers are the defaults, which also apply to keys missing from the file.
	 */
	private static final class Settings {
		private boolean enabled = false;
		@SerializedName("default")
		private boolean appliesByDefault = true;
		private List<PlayerEntry> players = new ArrayList<>();
	}

	private static final class PlayerEntry {
		private UUID uuid;
		private String name;
		private boolean value;
	}
}
