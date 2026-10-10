package com.cabbage.rtmap.client.waypoint;

/**
 * Converting coordinates between the overworld and the nether, where one block is eight blocks of the other.
 * Other dimensions (the end, modded ones) have no such relation, so nothing is converted for them.
 */
public final class DimensionScale {
	public static final String OVERWORLD = "minecraft:overworld";
	public static final String NETHER = "minecraft:the_nether";

	private DimensionScale() {
	}

	/** The dimension this one is linked to by portals, or null if it has none. */
	public static String counterpart(String dimension) {
		if (OVERWORLD.equals(dimension)) {
			return NETHER;
		}
		if (NETHER.equals(dimension)) {
			return OVERWORLD;
		}
		return null;
	}

	/** What to multiply a coordinate in {@code from} by to get it in {@code to}; 0 if they are not related. */
	public static double factor(String from, String to) {
		if (from.equals(to)) {
			return 1.0;
		}
		if (OVERWORLD.equals(from) && NETHER.equals(to)) {
			return 1.0 / 8.0;
		}
		if (NETHER.equals(from) && OVERWORLD.equals(to)) {
			return 8.0;
		}
		return 0.0;
	}

	/** A block coordinate converted to another dimension, rounded down like the game does. */
	public static int convert(int value, String from, String to) {
		double factor = factor(from, to);
		return (int) Math.floor(value * factor);
	}

	/**
	 * Where a waypoint appears on the map of {@code currentDimension}, as block coordinates of its centre, or null
	 * if it does not appear there. A waypoint from the other dimension of the pair is converted when
	 * {@code includeOther} is set.
	 */
	public static double[] positionOn(Waypoint waypoint, String currentDimension, boolean includeOther) {
		if (waypoint.dimension.equals(currentDimension)) {
			return new double[] {waypoint.x + 0.5, waypoint.z + 0.5};
		}
		if (includeOther && waypoint.dimension.equals(counterpart(currentDimension))) {
			double factor = factor(waypoint.dimension, currentDimension);
			return new double[] {(waypoint.x + 0.5) * factor, (waypoint.z + 0.5) * factor};
		}
		return null;
	}
}
