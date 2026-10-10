package com.cabbage.rtmap.client.waypoint;

import java.util.Locale;
import java.util.UUID;

/** One saved place. Plain data; everything that changes it goes through {@link WaypointStore}. */
public final class Waypoint {
	public enum Kind {
		NORMAL,
		/** Created automatically where the player died. */
		DEATH
	}

	public final UUID id;
	public final Kind kind;
	public final long created;

	public String name;
	public int x;
	public int y;
	public int z;
	/** Dimension id such as {@code minecraft:overworld}. */
	public String dimension;
	/** Id of the {@link WaypointGroup} this belongs to. */
	public String group;
	/** RGB, without alpha. */
	public int color;
	public boolean enabled = true;

	/**
	 * Death waypoints remove themselves when the player comes back. That only starts once the player has been
	 * clearly away, otherwise respawning next to the death point would delete it at once. Not saved.
	 */
	public transient boolean armed;

	public Waypoint(UUID id, Kind kind, long created, String name, int x, int y, int z, String dimension, String group, int color) {
		this.id = id;
		this.kind = kind;
		this.created = created;
		this.name = name;
		this.x = x;
		this.y = y;
		this.z = z;
		this.dimension = dimension;
		this.group = group;
		this.color = color;
	}

	public static Waypoint create(String name, int x, int y, int z, String dimension, String group, int color) {
		return new Waypoint(UUID.randomUUID(), Kind.NORMAL, System.currentTimeMillis(), name, x, y, z, dimension, group, color);
	}

	/** Up to two letters for the map icon: the first letters of the first two words. */
	public String initials() {
		StringBuilder result = new StringBuilder();
		for (String word : name.trim().split("\\s+")) {
			if (!word.isEmpty()) {
				result.appendCodePoint(word.codePointAt(0));
				if (result.codePointCount(0, result.length()) >= 2) {
					break;
				}
			}
		}
		return result.length() == 0 ? "?" : result.toString().toUpperCase(Locale.ROOT);
	}
}
