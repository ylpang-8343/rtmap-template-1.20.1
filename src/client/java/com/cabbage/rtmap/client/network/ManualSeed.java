package com.cabbage.rtmap.client.network;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;

import com.cabbage.rtmap.RTMap;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * A seed the player typed in, for worlds where the game cannot provide one (a server that has not allowed seed
 * sharing, or one without this mod). It lives in {@code seed.json} next to the world's map, and it is only a
 * fallback: a seed from the game or the server always wins. If the typed seed is wrong, seed-based features such
 * as slime chunks will simply be wrong, so this is the player's responsibility.
 */
public final class ManualSeed {
	private static Path file;
	private static Long seed;

	private ManualSeed() {
	}

	public static boolean isLoaded() {
		return file != null;
	}

	public static void load(Path worldDir) {
		unload();
		file = worldDir.resolve("seed.json");
		if (!Files.exists(file)) {
			return;
		}
		try {
			JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
			if (root.isJsonObject() && root.getAsJsonObject().has("seed")) {
				seed = root.getAsJsonObject().get("seed").getAsLong();
			}
		} catch (IOException | JsonParseException | IllegalStateException | NumberFormatException e) {
			RTMap.LOGGER.warn("Could not read {}", file, e);
		}
	}

	/** Forgets the seed in memory (the file stays). */
	public static void unload() {
		file = null;
		seed = null;
	}

	public static OptionalLong get() {
		Long value = seed;
		return value == null ? OptionalLong.empty() : OptionalLong.of(value);
	}

	public static void set(long value) {
		if (file == null) {
			return;
		}
		seed = value;
		JsonObject obj = new JsonObject();
		obj.addProperty("seed", value);
		write(obj.toString());
	}

	/** Removes the typed seed, including its file. */
	public static void clear() {
		seed = null;
		if (file != null) {
			try {
				Files.deleteIfExists(file);
			} catch (IOException e) {
				RTMap.LOGGER.warn("Could not delete {}", file, e);
			}
		}
	}

	private static void write(String json) {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, json, StandardCharsets.UTF_8);
		} catch (IOException e) {
			RTMap.LOGGER.warn("Could not write {}", file, e);
		}
	}

	/** Reads a seed the way the game does: a number is used as it is, any other text is hashed. */
	public static long parse(String text) {
		String trimmed = text.trim();
		try {
			return Long.parseLong(trimmed);
		} catch (NumberFormatException e) {
			return trimmed.hashCode();
		}
	}
}
