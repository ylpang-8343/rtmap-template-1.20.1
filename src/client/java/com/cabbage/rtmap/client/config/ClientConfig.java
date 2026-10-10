package com.cabbage.rtmap.client.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import com.cabbage.rtmap.RTMap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The player's own map settings, stored in <code>config/rtmap-client.json</code>. Out-of-range or unknown values
 * fall back to the defaults, so a hand-edited file can never break the map.
 *
 * <p>Changes are marked dirty and written by {@link #saveIfDirty()}, so dragging a slider or holding a key does
 * not hit the disk every frame.
 */
public final class ClientConfig {
	public enum Corner {
		TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT;

		public boolean isLeft() {
			return this == TOP_LEFT || this == BOTTOM_LEFT;
		}

		public boolean isTop() {
			return this == TOP_LEFT || this == TOP_RIGHT;
		}
	}

	/** Minimap edge lengths in GUI pixels. */
	public static final int[] MINIMAP_SIZES = {64, 96, 128, 160};
	/** Minimap zoom steps, in screen pixels per block. */
	public static final double[] MINIMAP_ZOOMS = {0.5, 0.75, 1.0, 1.5, 2.0, 3.0, 4.0};

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	// Defaults follow Xaero's minimap: square, top right, north up.
	private static boolean minimapEnabled = true;
	private static Corner minimapCorner = Corner.TOP_RIGHT;
	private static int minimapSize = 96;
	private static int minimapZoomIndex = 3;
	private static boolean minimapRotate = false;
	private static boolean showCoordinates = true;
	private static boolean showConvertedCoordinates = true;
	private static boolean showOtherDimensionWaypoints = true;
	private static boolean worldWaypoints = true;
	private static boolean worldBeams = true;
	private static boolean showDistance = true;
	private static boolean autoHideLabels = true;
	/** A beam is not drawn when the player is closer than this many blocks (horizontally). */
	private static int beamMinDistance = 4;
	/** Waypoints further away than this are not drawn in the world; 0 means no limit. */
	private static int worldMaxDistance = 0;
	private static boolean createDeathWaypoints = true;
	private static int deathWaypointRemoveDistance = 3;
	/** {x} {y} {z} are the block; {cx} {cz} its centre; {dim} the dimension id; {name} the waypoint name. */
	private static String teleportCommand = "execute in {dim} run tp @s {cx} {y} {cz}";
	private static final Map<String, Boolean> LAYERS = new HashMap<>();

	private static boolean dirty;

	private ClientConfig() {
	}

	public static boolean minimapEnabled() {
		return minimapEnabled;
	}

	public static void setMinimapEnabled(boolean value) {
		minimapEnabled = value;
		dirty = true;
	}

	public static Corner minimapCorner() {
		return minimapCorner;
	}

	public static void setMinimapCorner(Corner value) {
		minimapCorner = value;
		dirty = true;
	}

	public static int minimapSize() {
		return minimapSize;
	}

	public static void setMinimapSize(int value) {
		minimapSize = nearestSize(value);
		dirty = true;
	}

	public static double minimapZoom() {
		return MINIMAP_ZOOMS[minimapZoomIndex];
	}

	/** Moves one step through {@link #MINIMAP_ZOOMS}; positive zooms in. */
	public static void stepMinimapZoom(int steps) {
		minimapZoomIndex = Math.max(0, Math.min(MINIMAP_ZOOMS.length - 1, minimapZoomIndex + steps));
		dirty = true;
	}

	public static boolean minimapRotate() {
		return minimapRotate;
	}

	public static void setMinimapRotate(boolean value) {
		minimapRotate = value;
		dirty = true;
	}

	public static boolean showCoordinates() {
		return showCoordinates;
	}

	public static void setShowCoordinates(boolean value) {
		showCoordinates = value;
		dirty = true;
	}

	/** Show the matching overworld/nether coordinates next to the current ones. */
	public static boolean showConvertedCoordinates() {
		return showConvertedCoordinates;
	}

	public static void setShowConvertedCoordinates(boolean value) {
		showConvertedCoordinates = value;
		dirty = true;
	}

	/** Show the other dimension's waypoints, converted, on the map. */
	public static boolean showOtherDimensionWaypoints() {
		return showOtherDimensionWaypoints;
	}

	public static void setShowOtherDimensionWaypoints(boolean value) {
		showOtherDimensionWaypoints = value;
		dirty = true;
	}

	public static boolean worldWaypoints() {
		return worldWaypoints;
	}

	public static void setWorldWaypoints(boolean value) {
		worldWaypoints = value;
		dirty = true;
	}

	public static boolean worldBeams() {
		return worldBeams;
	}

	public static void setWorldBeams(boolean value) {
		worldBeams = value;
		dirty = true;
	}

	public static boolean showDistance() {
		return showDistance;
	}

	public static void setShowDistance(boolean value) {
		showDistance = value;
		dirty = true;
	}

	public static boolean autoHideLabels() {
		return autoHideLabels;
	}

	public static int beamMinDistance() {
		return beamMinDistance;
	}

	public static int worldMaxDistance() {
		return worldMaxDistance;
	}

	public static boolean createDeathWaypoints() {
		return createDeathWaypoints;
	}

	public static void setCreateDeathWaypoints(boolean value) {
		createDeathWaypoints = value;
		dirty = true;
	}

	/** Distance in blocks at which a death waypoint removes itself when the player comes back. */
	public static int deathWaypointRemoveDistance() {
		return deathWaypointRemoveDistance;
	}

	/** The command sent to teleport to a waypoint, without the leading slash. */
	public static String teleportCommand() {
		return teleportCommand;
	}

	public static boolean isLayerEnabled(String id, boolean defaultValue) {
		return LAYERS.getOrDefault(id, defaultValue);
	}

	public static void setLayerEnabled(String id, boolean value) {
		LAYERS.put(id, value);
		dirty = true;
	}

	private static int nearestSize(int value) {
		int best = MINIMAP_SIZES[0];
		for (int size : MINIMAP_SIZES) {
			if (Math.abs(size - value) < Math.abs(best - value)) {
				best = size;
			}
		}
		return best;
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("rtmap-client.json");
	}

	public static void load() {
		Path path = path();
		if (!Files.exists(path)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			JsonElement root = JsonParser.parseReader(reader);
			if (!root.isJsonObject()) {
				throw new JsonParseException("root is not an object");
			}
			JsonObject obj = root.getAsJsonObject();

			minimapEnabled = readBoolean(obj, "minimap_enabled", minimapEnabled);
			minimapCorner = readCorner(obj, "minimap_corner", minimapCorner);
			minimapSize = nearestSize(readInt(obj, "minimap_size", minimapSize));
			minimapZoomIndex = Math.max(0, Math.min(MINIMAP_ZOOMS.length - 1, readInt(obj, "minimap_zoom_index", minimapZoomIndex)));
			minimapRotate = readBoolean(obj, "minimap_rotate", minimapRotate);
			showCoordinates = readBoolean(obj, "show_coordinates", showCoordinates);
			showConvertedCoordinates = readBoolean(obj, "show_converted_coordinates", showConvertedCoordinates);
			showOtherDimensionWaypoints = readBoolean(obj, "show_other_dimension_waypoints", showOtherDimensionWaypoints);
			worldWaypoints = readBoolean(obj, "world_waypoints", worldWaypoints);
			worldBeams = readBoolean(obj, "world_beams", worldBeams);
			showDistance = readBoolean(obj, "world_show_distance", showDistance);
			autoHideLabels = readBoolean(obj, "world_auto_hide_labels", autoHideLabels);
			beamMinDistance = Math.max(0, Math.min(64, readInt(obj, "beam_min_distance", beamMinDistance)));
			worldMaxDistance = Math.max(0, Math.min(10000, readInt(obj, "world_max_distance", worldMaxDistance)));
			createDeathWaypoints = readBoolean(obj, "create_death_waypoints", createDeathWaypoints);
			deathWaypointRemoveDistance = Math.max(2, Math.min(64, readInt(obj, "death_waypoint_remove_distance", deathWaypointRemoveDistance)));
			teleportCommand = readString(obj, "teleport_command", teleportCommand);

			LAYERS.clear();
			JsonElement layers = obj.get("layers");
			if (layers != null && layers.isJsonObject()) {
				for (Map.Entry<String, JsonElement> entry : layers.getAsJsonObject().entrySet()) {
					JsonElement value = entry.getValue();
					if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
						LAYERS.put(entry.getKey(), value.getAsBoolean());
					}
				}
			}
		} catch (IOException | JsonParseException | IllegalStateException e) {
			RTMap.LOGGER.warn("Could not read {}; using the defaults", path, e);
		}
	}

	public static void saveIfDirty() {
		if (!dirty) {
			return;
		}
		dirty = false;

		JsonObject obj = new JsonObject();
		obj.addProperty("minimap_enabled", minimapEnabled);
		obj.addProperty("minimap_corner", minimapCorner.name().toLowerCase(Locale.ROOT));
		obj.addProperty("minimap_size", minimapSize);
		obj.addProperty("minimap_zoom_index", minimapZoomIndex);
		obj.addProperty("minimap_rotate", minimapRotate);
		obj.addProperty("show_coordinates", showCoordinates);
		obj.addProperty("show_converted_coordinates", showConvertedCoordinates);
		obj.addProperty("show_other_dimension_waypoints", showOtherDimensionWaypoints);
		obj.addProperty("world_waypoints", worldWaypoints);
		obj.addProperty("world_beams", worldBeams);
		obj.addProperty("world_show_distance", showDistance);
		obj.addProperty("world_auto_hide_labels", autoHideLabels);
		obj.addProperty("beam_min_distance", beamMinDistance);
		obj.addProperty("world_max_distance", worldMaxDistance);
		obj.addProperty("create_death_waypoints", createDeathWaypoints);
		obj.addProperty("death_waypoint_remove_distance", deathWaypointRemoveDistance);
		obj.addProperty("teleport_command", teleportCommand);
		JsonObject layers = new JsonObject();
		LAYERS.forEach(layers::addProperty);
		obj.add("layers", layers);

		Path path = path();
		Path temp = path.resolveSibling(path.getFileName() + ".tmp");
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
		} catch (IOException e) {
			dirty = true; // try again later
			RTMap.LOGGER.warn("Could not write {}", path, e);
		}
	}

	private static boolean readBoolean(JsonObject obj, String key, boolean fallback) {
		JsonElement element = obj.get(key);
		if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()) {
			return element.getAsBoolean();
		}
		return fallback;
	}

	private static int readInt(JsonObject obj, String key, int fallback) {
		JsonElement element = obj.get(key);
		if (element == null) {
			return fallback;
		}
		try {
			return element.getAsInt();
		} catch (RuntimeException e) {
			return fallback;
		}
	}

	private static String readString(JsonObject obj, String key, String fallback) {
		JsonElement element = obj.get(key);
		if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
			String value = element.getAsString().trim();
			// A leading slash is allowed in the file; the game wants the command without it.
			value = value.startsWith("/") ? value.substring(1) : value;
			return value.isEmpty() ? fallback : value;
		}
		return fallback;
	}

	private static Corner readCorner(JsonObject obj, String key, Corner fallback) {
		JsonElement element = obj.get(key);
		if (element != null && element.isJsonPrimitive()) {
			try {
				return Corner.valueOf(element.getAsString().toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException ignored) {
				// fall through to the default
			}
		}
		return fallback;
	}
}
