package com.cabbage.rtmap.client.waypoint;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.cabbage.rtmap.RTMap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * The waypoints of the world the player is in, kept in {@code waypoints.json} next to that world's map.
 * Everything runs on the client thread. Changes mark the store dirty; {@link #saveIfDirty()} writes it.
 *
 * <p>The file is our own format. A bad entry is skipped rather than failing the whole file, and a file that
 * cannot be read at all is left untouched on disk (never overwritten with an empty list).
 */
public final class WaypointStore {
	private static final int FORMAT_VERSION = 1;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static final List<Waypoint> WAYPOINTS = new ArrayList<>();
	private static final List<WaypointGroup> GROUPS = new ArrayList<>();

	private static Path file;
	private static boolean dirty;
	/** Set when the file exists but could not be read, so we never save over it. */
	private static boolean readFailed;

	private WaypointStore() {
	}

	public static boolean isLoaded() {
		return file != null;
	}

	/** Loads the waypoints that belong to the world stored in {@code worldDir}. */
	public static void load(Path worldDir) {
		clear();
		file = worldDir.resolve("waypoints.json");
		ensureBuiltinGroups();

		if (!Files.exists(file)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			JsonElement root = JsonParser.parseReader(reader);
			if (!root.isJsonObject()) {
				throw new JsonParseException("root is not an object");
			}
			JsonObject obj = root.getAsJsonObject();
			readGroups(obj.getAsJsonArray("groups"));
			readWaypoints(obj.getAsJsonArray("waypoints"));
			ensureBuiltinGroups();
		} catch (IOException | JsonParseException | IllegalStateException | ClassCastException e) {
			readFailed = true;
			RTMap.LOGGER.error("Could not read {}; waypoints are not saved this session so the file stays as it is", file, e);
		}
	}

	private static void ensureBuiltinGroups() {
		if (group(WaypointGroup.DEFAULT_ID) == null) {
			GROUPS.add(0, new WaypointGroup(WaypointGroup.DEFAULT_ID, "Default", true));
		}
		if (group(WaypointGroup.DEATH_ID) == null) {
			GROUPS.add(Math.min(1, GROUPS.size()), new WaypointGroup(WaypointGroup.DEATH_ID, "Death", true));
		}
	}

	private static void readGroups(JsonArray array) {
		if (array == null) {
			return;
		}
		for (JsonElement element : array) {
			try {
				JsonObject obj = element.getAsJsonObject();
				String id = obj.get("id").getAsString();
				if (group(id) != null) {
					continue;
				}
				WaypointGroup group = new WaypointGroup(id, obj.get("name").getAsString(),
					WaypointGroup.DEFAULT_ID.equals(id) || WaypointGroup.DEATH_ID.equals(id));
				group.enabled = !obj.has("enabled") || obj.get("enabled").getAsBoolean();
				GROUPS.add(group);
			} catch (RuntimeException e) {
				RTMap.LOGGER.warn("Skipping a bad waypoint group entry", e);
			}
		}
	}

	private static void readWaypoints(JsonArray array) {
		if (array == null) {
			return;
		}
		for (JsonElement element : array) {
			try {
				JsonObject obj = element.getAsJsonObject();
				Waypoint.Kind kind = obj.has("kind")
					? Waypoint.Kind.valueOf(obj.get("kind").getAsString().toUpperCase(Locale.ROOT))
					: Waypoint.Kind.NORMAL;
				String group = obj.has("group") ? obj.get("group").getAsString() : WaypointGroup.DEFAULT_ID;
				if (group(group) == null) {
					group = WaypointGroup.DEFAULT_ID;
				}
				Waypoint waypoint = new Waypoint(
					UUID.fromString(obj.get("id").getAsString()),
					kind,
					obj.has("created") ? obj.get("created").getAsLong() : 0L,
					obj.get("name").getAsString(),
					obj.get("x").getAsInt(),
					obj.get("y").getAsInt(),
					obj.get("z").getAsInt(),
					obj.get("dimension").getAsString(),
					group,
					obj.get("color").getAsInt() & 0xFFFFFF);
				waypoint.enabled = !obj.has("enabled") || obj.get("enabled").getAsBoolean();
				WAYPOINTS.add(waypoint);
			} catch (RuntimeException e) {
				RTMap.LOGGER.warn("Skipping a bad waypoint entry", e);
			}
		}
	}

	/** Writes the file if anything changed. Safe to call often. */
	public static void saveIfDirty() {
		if (!dirty || file == null || readFailed) {
			return;
		}
		dirty = false;

		JsonObject root = new JsonObject();
		root.addProperty("version", FORMAT_VERSION);
		JsonArray groups = new JsonArray();
		for (WaypointGroup group : GROUPS) {
			JsonObject obj = new JsonObject();
			obj.addProperty("id", group.id);
			obj.addProperty("name", group.name);
			obj.addProperty("enabled", group.enabled);
			groups.add(obj);
		}
		root.add("groups", groups);
		JsonArray waypoints = new JsonArray();
		for (Waypoint waypoint : WAYPOINTS) {
			JsonObject obj = new JsonObject();
			obj.addProperty("id", waypoint.id.toString());
			obj.addProperty("kind", waypoint.kind.name().toLowerCase(Locale.ROOT));
			obj.addProperty("created", waypoint.created);
			obj.addProperty("name", waypoint.name);
			obj.addProperty("x", waypoint.x);
			obj.addProperty("y", waypoint.y);
			obj.addProperty("z", waypoint.z);
			obj.addProperty("dimension", waypoint.dimension);
			obj.addProperty("group", waypoint.group);
			obj.addProperty("color", waypoint.color);
			obj.addProperty("enabled", waypoint.enabled);
			waypoints.add(obj);
		}
		root.add("waypoints", waypoints);

		Path temp = file.resolveSibling(file.getFileName() + ".tmp");
		try {
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
				GSON.toJson(root, writer);
			}
			try {
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			dirty = true; // try again later
			RTMap.LOGGER.warn("Could not write {}", file, e);
		}
	}

	/** Forgets everything. Call {@link #saveIfDirty()} first if the data should be kept. */
	public static void clear() {
		WAYPOINTS.clear();
		GROUPS.clear();
		file = null;
		dirty = false;
		readFailed = false;
	}

	public static List<Waypoint> waypoints() {
		return Collections.unmodifiableList(WAYPOINTS);
	}

	public static List<WaypointGroup> groups() {
		return Collections.unmodifiableList(GROUPS);
	}

	public static WaypointGroup group(String id) {
		for (WaypointGroup group : GROUPS) {
			if (group.id.equals(id)) {
				return group;
			}
		}
		return null;
	}

	/** Whether a waypoint should be drawn: it and its group are both switched on. */
	public static boolean isVisible(Waypoint waypoint) {
		WaypointGroup group = group(waypoint.group);
		return waypoint.enabled && (group == null || group.enabled);
	}

	public static void add(Waypoint waypoint) {
		WAYPOINTS.add(waypoint);
		dirty = true;
	}

	public static void remove(Waypoint waypoint) {
		if (WAYPOINTS.remove(waypoint)) {
			dirty = true;
		}
	}

	/** Call after changing a waypoint's fields. */
	public static void markChanged() {
		dirty = true;
	}

	public static WaypointGroup addGroup(String name) {
		String id = uniqueGroupId(name);
		WaypointGroup group = new WaypointGroup(id, name.trim(), false);
		GROUPS.add(group);
		dirty = true;
		return group;
	}

	private static String uniqueGroupId(String name) {
		String base = name.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
		if (base.isEmpty() || WaypointGroup.DEFAULT_ID.equals(base) || WaypointGroup.DEATH_ID.equals(base)) {
			base = "group";
		}
		String id = base;
		for (int n = 2; group(id) != null; n++) {
			id = base + "_" + n;
		}
		return id;
	}
}
