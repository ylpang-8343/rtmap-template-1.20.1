package com.cabbage.rtmap.client.portal;

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

import com.cabbage.rtmap.RTMap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * The nether portals seen in the current world, saved as {@code portals.json} next to that world's map.
 * Same rules as the waypoint file: a bad entry is skipped, and an unreadable file is never overwritten.
 */
public final class PortalStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	/** Two portal sightings closer than this (in blocks) are the same portal. */
	private static final int SAME_PORTAL_DISTANCE = 3;

	private static final List<Portal> PORTALS = new ArrayList<>();

	private static Path file;
	private static boolean dirty;
	private static boolean readFailed;

	private PortalStore() {
	}

	public static boolean isLoaded() {
		return file != null;
	}

	public static void load(Path worldDir) {
		clear();
		file = worldDir.resolve("portals.json");
		if (!Files.exists(file)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			JsonElement root = JsonParser.parseReader(reader);
			if (!root.isJsonObject() || !root.getAsJsonObject().has("portals")) {
				throw new JsonParseException("no portals list");
			}
			for (JsonElement element : root.getAsJsonObject().getAsJsonArray("portals")) {
				try {
					JsonObject obj = element.getAsJsonObject();
					PORTALS.add(new Portal(obj.get("dimension").getAsString(),
						obj.get("x").getAsInt(), obj.get("y").getAsInt(), obj.get("z").getAsInt(),
						obj.get("ax").getAsInt(), obj.get("ay").getAsInt(), obj.get("az").getAsInt()));
				} catch (RuntimeException e) {
					RTMap.LOGGER.warn("Skipping a bad portal entry", e);
				}
			}
		} catch (IOException | JsonParseException | IllegalStateException | ClassCastException e) {
			readFailed = true;
			RTMap.LOGGER.error("Could not read {}; portals are not saved this session so the file stays as it is", file, e);
		}
	}

	public static void saveIfDirty() {
		if (!dirty || file == null || readFailed) {
			return;
		}
		dirty = false;

		JsonArray array = new JsonArray();
		for (Portal portal : PORTALS) {
			JsonObject obj = new JsonObject();
			obj.addProperty("dimension", portal.dimension());
			obj.addProperty("x", portal.x());
			obj.addProperty("y", portal.y());
			obj.addProperty("z", portal.z());
			obj.addProperty("ax", portal.anchorX());
			obj.addProperty("ay", portal.anchorY());
			obj.addProperty("az", portal.anchorZ());
			array.add(obj);
		}
		JsonObject root = new JsonObject();
		root.addProperty("version", 1);
		root.add("portals", array);

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
			dirty = true;
			RTMap.LOGGER.warn("Could not write {}", file, e);
		}
	}

	public static void clear() {
		PORTALS.clear();
		file = null;
		dirty = false;
		readFailed = false;
	}

	public static List<Portal> portals() {
		return Collections.unmodifiableList(PORTALS);
	}

	/** Adds a portal unless the same one is already known. */
	public static void add(Portal portal) {
		for (Portal known : PORTALS) {
			if (known.dimension().equals(portal.dimension())
				&& Math.abs(known.x() - portal.x()) <= SAME_PORTAL_DISTANCE
				&& Math.abs(known.y() - portal.y()) <= SAME_PORTAL_DISTANCE
				&& Math.abs(known.z() - portal.z()) <= SAME_PORTAL_DISTANCE) {
				return;
			}
		}
		PORTALS.add(portal);
		dirty = true;
	}

	public static void remove(Portal portal) {
		if (PORTALS.remove(portal)) {
			dirty = true;
		}
	}
}
