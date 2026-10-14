package com.cabbage.rtmap.client.measure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.cabbage.rtmap.client.waypoint.DimensionScale;
import com.cabbage.rtmap.client.waypoint.Waypoints;
import net.minecraft.text.Text;

/**
 * The measuring tool: a ruler that follows any number of points, a rectangle and a circle. It only holds the points
 * and does the arithmetic; the map layer draws them and the full-screen map adds points when you click.
 *
 * <p>Points are block positions in one dimension and count as block centres, so the distance between two
 * neighbouring blocks is exactly 1. Nothing is saved: a measurement lasts until you clear it or leave the world.
 */
public final class MeasureTool {
	public enum Mode {
		OFF,
		RULER,
		RECTANGLE,
		CIRCLE
	}

	private static final int MAX_POINTS = 64;
	/** Counting the chunks a huge circle touches would take noticeable time and nobody needs the number. */
	private static final double MAX_CIRCLE_RADIUS_FOR_CHUNK_COUNT = 8192;

	private static final List<int[]> POINTS = new ArrayList<>();
	private static Mode mode = Mode.OFF;
	private static String dimension;

	private MeasureTool() {
	}

	public static Mode mode() {
		return mode;
	}

	public static boolean active() {
		return mode != Mode.OFF;
	}

	/** Switching tool starts a fresh measurement. */
	public static void setMode(Mode newMode) {
		mode = newMode;
		clear();
	}

	public static List<int[]> points() {
		return Collections.unmodifiableList(POINTS);
	}

	/** The dimension the points are in, or null if there are none. */
	public static String dimension() {
		return dimension;
	}

	public static void clear() {
		POINTS.clear();
		dimension = null;
	}

	public static void removeLast() {
		if (!POINTS.isEmpty()) {
			POINTS.remove(POINTS.size() - 1);
		}
		if (POINTS.isEmpty()) {
			dimension = null;
		}
	}

	/** Adds a block position. A rectangle or circle holds two points, so a third click starts over. */
	public static void addPoint(String pointDimension, int x, int z) {
		if (mode == Mode.OFF) {
			return;
		}
		if (dimension != null && !dimension.equals(pointDimension)) {
			clear();
		}
		boolean twoPointShape = mode == Mode.RECTANGLE || mode == Mode.CIRCLE;
		if ((twoPointShape && POINTS.size() >= 2) || POINTS.size() >= MAX_POINTS) {
			if (twoPointShape) {
				POINTS.clear();
			} else {
				return;
			}
		}
		dimension = pointDimension;
		POINTS.add(new int[] {x, z});
	}

	/** Whether the rectangle or circle has both of its points. */
	public static boolean isComplete() {
		return (mode == Mode.RECTANGLE || mode == Mode.CIRCLE) && POINTS.size() == 2;
	}

	public static double distance(int[] a, int[] b) {
		return Math.hypot(b[0] - a[0], b[1] - a[1]);
	}

	/** The lines of text describing the current measurement. */
	public static List<Text> summary() {
		List<Text> lines = new ArrayList<>();
		switch (mode) {
			case OFF -> {
			}
			case RULER -> rulerSummary(lines);
			case RECTANGLE -> rectangleSummary(lines);
			case CIRCLE -> circleSummary(lines);
		}
		return lines;
	}

	private static void rulerSummary(List<Text> lines) {
		if (POINTS.size() < 2) {
			lines.add(Text.translatable("measure.rtmap.hint_ruler"));
			return;
		}
		double total = 0;
		for (int i = 1; i < POINTS.size(); i++) {
			total += distance(POINTS.get(i - 1), POINTS.get(i));
		}
		int[] first = POINTS.get(0);
		int[] last = POINTS.get(POINTS.size() - 1);
		double straight = distance(first, last);

		lines.add(Text.translatable("measure.rtmap.ruler_total", number(total), POINTS.size() - 1));
		if (POINTS.size() > 2) {
			lines.add(Text.translatable("measure.rtmap.ruler_straight", number(straight), last[0] - first[0], last[1] - first[1]));
		} else {
			lines.add(Text.translatable("measure.rtmap.ruler_delta", last[0] - first[0], last[1] - first[1]));
		}
		lines.add(Text.translatable("measure.rtmap.chunks_long", number(total / 16.0)));

		// What the straight line would be in the other dimension of the overworld/nether pair.
		String other = dimension == null ? null : DimensionScale.counterpart(dimension);
		if (other != null) {
			double factor = DimensionScale.factor(dimension, other);
			lines.add(Text.translatable("measure.rtmap.other_dimension", Waypoints.dimensionName(other), number(straight * factor)));
		}
	}

	private static void rectangleSummary(List<Text> lines) {
		if (POINTS.size() < 2) {
			lines.add(Text.translatable("measure.rtmap.hint_rectangle"));
			return;
		}
		int[] a = POINTS.get(0);
		int[] b = POINTS.get(1);
		int minX = Math.min(a[0], b[0]);
		int maxX = Math.max(a[0], b[0]);
		int minZ = Math.min(a[1], b[1]);
		int maxZ = Math.max(a[1], b[1]);
		long width = (long) maxX - minX + 1;
		long length = (long) maxZ - minZ + 1;

		int chunkMinX = Math.floorDiv(minX, 16);
		int chunkMaxX = Math.floorDiv(maxX, 16);
		int chunkMinZ = Math.floorDiv(minZ, 16);
		int chunkMaxZ = Math.floorDiv(maxZ, 16);
		long chunksWide = (long) chunkMaxX - chunkMinX + 1;
		long chunksLong = (long) chunkMaxZ - chunkMinZ + 1;

		int regionMinX = Math.floorDiv(minX, 512);
		int regionMaxX = Math.floorDiv(maxX, 512);
		int regionMinZ = Math.floorDiv(minZ, 512);
		int regionMaxZ = Math.floorDiv(maxZ, 512);
		long regions = ((long) regionMaxX - regionMinX + 1) * ((long) regionMaxZ - regionMinZ + 1);
		String regionRange = regions == 1
			? "r." + regionMinX + "." + regionMinZ
			: "r." + regionMinX + "." + regionMinZ + " – r." + regionMaxX + "." + regionMaxZ;

		lines.add(Text.translatable("measure.rtmap.rect_size", width, length, whole(width * length)));
		lines.add(Text.translatable("measure.rtmap.rect_corners", minX + ", " + minZ, maxX + ", " + maxZ));
		lines.add(Text.translatable("measure.rtmap.rect_chunks", whole(chunksWide * chunksLong), chunksWide, chunksLong));
		lines.add(Text.translatable("measure.rtmap.rect_regions", regions, regionRange));
	}

	private static void circleSummary(List<Text> lines) {
		if (POINTS.size() < 2) {
			lines.add(Text.translatable("measure.rtmap.hint_circle"));
			return;
		}
		int[] center = POINTS.get(0);
		double radius = distance(center, POINTS.get(1));
		double area = Math.PI * radius * radius;

		lines.add(Text.translatable("measure.rtmap.circle_radius", number(radius), number(radius * 2)));
		lines.add(Text.translatable("measure.rtmap.circle_area", whole(Math.round(area))));
		if (radius <= MAX_CIRCLE_RADIUS_FOR_CHUNK_COUNT) {
			lines.add(Text.translatable("measure.rtmap.circle_chunks", whole(chunksTouched(center[0], center[1], radius))));
		}
		lines.add(Text.translatable("measure.rtmap.circle_center", center[0] + ", " + center[1]));
	}

	/** How many chunks a circle overlaps: those whose nearest point is no further than the radius from the centre. */
	public static long chunksTouched(double centerX, double centerZ, double radius) {
		int minChunkX = (int) Math.floor((centerX - radius) / 16.0);
		int maxChunkX = (int) Math.floor((centerX + radius) / 16.0);
		int minChunkZ = (int) Math.floor((centerZ - radius) / 16.0);
		int maxChunkZ = (int) Math.floor((centerZ + radius) / 16.0);
		long count = 0;
		for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
			double nearestZ = Math.max(chunkZ * 16.0, Math.min(centerZ, chunkZ * 16.0 + 15));
			for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
				double nearestX = Math.max(chunkX * 16.0, Math.min(centerX, chunkX * 16.0 + 15));
				double dx = nearestX - centerX;
				double dz = nearestZ - centerZ;
				if (dx * dx + dz * dz <= radius * radius) {
					count++;
				}
			}
		}
		return count;
	}

	/** One decimal place, with a dot regardless of the system language. */
	public static String number(double value) {
		return String.format(Locale.ROOT, "%.1f", value);
	}

	/** A whole number with thousands separators. */
	private static String whole(long value) {
		return String.format(Locale.ROOT, "%,d", value);
	}
}
