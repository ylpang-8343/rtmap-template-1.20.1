package com.cabbage.rtmap.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.cabbage.rtmap.RTMap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Server-side settings, loaded only from the server's own config directory.
 * There is deliberately no way for a client to read or change these values.
 * Every numeric value is clamped, so a bad file can never widen access beyond the limits below.
 */
public final class ServerConfig {
	/** Never lower than vanilla gamemaster level, so a bad config cannot let ordinary players run /rtmap. */
	public static final int MIN_COMMAND_LEVEL = 2;
	public static final int MAX_COMMAND_LEVEL = 4;
	public static final int MIN_REQUEST_LIMIT = 1;
	public static final int MAX_REQUEST_LIMIT = 60;
	public static final int MIN_WINDOW_SECONDS = 10;
	public static final int MAX_WINDOW_SECONDS = 3600;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final ServerConfig DEFAULTS = new ServerConfig(false, 3, 3, 30);

	private static volatile ServerConfig current = DEFAULTS;

	private final boolean seedSharing;
	private final int commandPermissionLevel;
	private final int seedRequestLimit;
	private final int seedRequestWindowSeconds;

	private ServerConfig(boolean seedSharing, int commandPermissionLevel, int seedRequestLimit, int seedRequestWindowSeconds) {
		this.seedSharing = seedSharing;
		this.commandPermissionLevel = commandPermissionLevel;
		this.seedRequestLimit = seedRequestLimit;
		this.seedRequestWindowSeconds = seedRequestWindowSeconds;
	}

	public static ServerConfig get() {
		return current;
	}

	public boolean seedSharing() {
		return seedSharing;
	}

	public int commandPermissionLevel() {
		return commandPermissionLevel;
	}

	public int seedRequestLimit() {
		return seedRequestLimit;
	}

	public int seedRequestWindowSeconds() {
		return seedRequestWindowSeconds;
	}

	public ServerConfig withSeedSharing(boolean enabled) {
		return new ServerConfig(enabled, commandPermissionLevel, seedRequestLimit, seedRequestWindowSeconds);
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("rtmap-server.json");
	}

	/**
	 * Loads the config file. If it is missing, defaults are written. If it cannot be parsed, the previous
	 * config stays in effect and false is returned.
	 */
	public static boolean load() {
		Path path = path();
		if (!Files.exists(path)) {
			current = DEFAULTS;
			save(DEFAULTS);
			return true;
		}

		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			JsonElement root = JsonParser.parseReader(reader);
			if (!root.isJsonObject()) {
				throw new JsonParseException("root is not an object");
			}
			JsonObject obj = root.getAsJsonObject();
			current = new ServerConfig(
				readBoolean(obj, "seed_sharing", DEFAULTS.seedSharing),
				readInt(obj, "command_permission_level", DEFAULTS.commandPermissionLevel, MIN_COMMAND_LEVEL, MAX_COMMAND_LEVEL),
				readInt(obj, "seed_request_limit", DEFAULTS.seedRequestLimit, MIN_REQUEST_LIMIT, MAX_REQUEST_LIMIT),
				readInt(obj, "seed_request_window_seconds", DEFAULTS.seedRequestWindowSeconds, MIN_WINDOW_SECONDS, MAX_WINDOW_SECONDS)
			);
			return true;
		} catch (IOException | JsonParseException | IllegalStateException e) {
			RTMap.LOGGER.error("Could not read {}; keeping the previous settings", path, e);
			return false;
		}
	}

	/** Applies the config in memory, then persists it. Returns false if persisting failed. */
	public static boolean update(ServerConfig config) {
		current = config;
		return save(config);
	}

	private static boolean save(ServerConfig config) {
		Path path = path();
		Path temp = path.resolveSibling(path.getFileName() + ".tmp");
		JsonObject obj = new JsonObject();
		obj.addProperty("seed_sharing", config.seedSharing);
		obj.addProperty("command_permission_level", config.commandPermissionLevel);
		obj.addProperty("seed_request_limit", config.seedRequestLimit);
		obj.addProperty("seed_request_window_seconds", config.seedRequestWindowSeconds);

		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
				GSON.toJson(obj, writer);
			}
			try {
				Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
			}
			return true;
		} catch (IOException e) {
			RTMap.LOGGER.error("Could not write {}", path, e);
			return false;
		}
	}

	private static boolean readBoolean(JsonObject obj, String key, boolean fallback) {
		JsonElement element = obj.get(key);
		if (element == null) {
			return fallback;
		}
		if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()) {
			return element.getAsBoolean();
		}
		RTMap.LOGGER.warn("Config value '{}' is not a boolean; using {}", key, fallback);
		return fallback;
	}

	private static int readInt(JsonObject obj, String key, int fallback, int min, int max) {
		JsonElement element = obj.get(key);
		if (element == null) {
			return fallback;
		}
		int value;
		try {
			value = element.getAsInt();
		} catch (RuntimeException e) {
			RTMap.LOGGER.warn("Config value '{}' is not an integer; using {}", key, fallback);
			return fallback;
		}
		int clamped = Math.max(min, Math.min(max, value));
		if (clamped != value) {
			RTMap.LOGGER.warn("Config value '{}'={} is outside [{}, {}]; using {}", key, value, min, max, clamped);
		}
		return clamped;
	}
}
