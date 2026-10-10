package com.cabbage.rtmap.client.waypoint;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import com.cabbage.rtmap.RTMap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Exporting waypoints to a file and importing them back, in RTMap's own format, so they can be moved between
 * worlds or handed to someone else. Files go in {@code <game dir>/rtmap/exports} and are read from
 * {@code <game dir>/rtmap/imports}. Imported files are untrusted: every entry is checked, nothing is ever replaced,
 * and the total is capped.
 */
public final class WaypointFiles {
	private static final String FORMAT = "rtmap-waypoints";
	private static final int VERSION = 1;
	private static final int MAX_ENTRIES_PER_FILE = 5000;
	private static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
	private static final int COORDINATE_LIMIT = 30_000_000;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** What an import did, for showing to the player. */
	public record ImportResult(int added, int skipped, int files) {
	}

	private WaypointFiles() {
	}

	public static Path exportDir() {
		return FabricLoader.getInstance().getGameDir().resolve("rtmap").resolve("exports");
	}

	public static Path importDir() {
		return FabricLoader.getInstance().getGameDir().resolve("rtmap").resolve("imports");
	}

	/** Writes every waypoint (except automatic death points) to a new file and returns its path. */
	public static Path export() throws IOException {
		JsonArray entries = new JsonArray();
		for (Waypoint waypoint : WaypointStore.waypoints()) {
			if (waypoint.kind == Waypoint.Kind.DEATH) {
				continue;
			}
			WaypointGroup group = WaypointStore.group(waypoint.group);
			JsonObject entry = new JsonObject();
			entry.addProperty("name", waypoint.name);
			entry.addProperty("x", waypoint.x);
			entry.addProperty("y", waypoint.y);
			entry.addProperty("z", waypoint.z);
			entry.addProperty("dimension", waypoint.dimension);
			entry.addProperty("color", waypoint.color);
			// By name rather than id, so the group still makes sense in another world.
			entry.addProperty("group", group == null ? "Default" : group.name);
			entries.add(entry);
		}
		JsonObject root = new JsonObject();
		root.addProperty("format", FORMAT);
		root.addProperty("version", VERSION);
		root.add("waypoints", entries);

		Files.createDirectories(exportDir());
		Path file = exportDir().resolve("waypoints-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + ".json");
		try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			GSON.toJson(root, writer);
		}
		return file;
	}

	/** Adds the waypoints from every {@code .json} file in the import folder. Existing ones are left alone. */
	public static ImportResult importAll() throws IOException {
		Files.createDirectories(importDir());
		int added = 0;
		int skipped = 0;
		int files = 0;
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(importDir(), "*.json")) {
			for (Path path : stream) {
				if (!Files.isRegularFile(path) || Files.size(path) > MAX_FILE_BYTES) {
					RTMap.LOGGER.warn("Skipping {}: not a regular file or larger than {} bytes", path, MAX_FILE_BYTES);
					continue;
				}
				int[] counts = importFile(path);
				if (counts != null) {
					files++;
					added += counts[0];
					skipped += counts[1];
				}
			}
		}
		return new ImportResult(added, skipped, files);
	}

	/** Returns {added, skipped}, or null if the file is not one of ours. */
	private static int[] importFile(Path path) {
		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			JsonElement root = JsonParser.parseReader(reader);
			if (!root.isJsonObject()) {
				return null;
			}
			JsonObject obj = root.getAsJsonObject();
			if (!obj.has("format") || !FORMAT.equals(obj.get("format").getAsString()) || !obj.has("waypoints")) {
				return null;
			}

			int added = 0;
			int skipped = 0;
			int seen = 0;
			for (JsonElement element : obj.getAsJsonArray("waypoints")) {
				if (++seen > MAX_ENTRIES_PER_FILE) {
					break;
				}
				Waypoint waypoint = readEntry(element);
				if (waypoint == null || exists(waypoint)) {
					skipped++;
					continue;
				}
				// Until now the group field held the group's name; only create the group for a waypoint we keep.
				waypoint.group = groupFor(waypoint.group).id;
				WaypointStore.add(waypoint);
				added++;
			}
			return new int[] {added, skipped};
		} catch (IOException | JsonParseException | IllegalStateException | ClassCastException e) {
			RTMap.LOGGER.warn("Could not import {}", path, e);
			return null;
		}
	}

	private static Waypoint readEntry(JsonElement element) {
		try {
			JsonObject entry = element.getAsJsonObject();
			String name = entry.get("name").getAsString().trim();
			int x = entry.get("x").getAsInt();
			int y = entry.get("y").getAsInt();
			int z = entry.get("z").getAsInt();
			String dimension = entry.get("dimension").getAsString();
			if (name.isEmpty() || name.length() > 48 || Math.abs(x) > COORDINATE_LIMIT || Math.abs(z) > COORDINATE_LIMIT
				|| y < -2048 || y > 4096 || !dimension.matches("[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,64}")) {
				return null;
			}
			int color = entry.has("color") ? entry.get("color").getAsInt() & 0xFFFFFF : Waypoints.randomColor();
			String groupName = entry.has("group") ? entry.get("group").getAsString().trim() : "Default";
			return Waypoint.create(name, x, y, z, dimension, groupName, color);
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** The group with this name, creating a custom one if there is none. The built-in names map to the built-ins. */
	private static WaypointGroup groupFor(String name) {
		if (name.isEmpty() || name.equalsIgnoreCase("default")) {
			return WaypointStore.group(WaypointGroup.DEFAULT_ID);
		}
		if (name.equalsIgnoreCase("death")) {
			return WaypointStore.group(WaypointGroup.DEATH_ID);
		}
		for (WaypointGroup group : WaypointStore.groups()) {
			if (group.name.equalsIgnoreCase(name)) {
				return group;
			}
		}
		return WaypointStore.addGroup(name.length() > 32 ? name.substring(0, 32) : name);
	}

	private static boolean exists(Waypoint candidate) {
		for (Waypoint waypoint : WaypointStore.waypoints()) {
			if (waypoint.name.equals(candidate.name) && waypoint.x == candidate.x && waypoint.y == candidate.y
				&& waypoint.z == candidate.z && waypoint.dimension.equals(candidate.dimension)) {
				return true;
			}
		}
		return false;
	}
}
