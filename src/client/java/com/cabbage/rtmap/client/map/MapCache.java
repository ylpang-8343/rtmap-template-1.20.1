package com.cabbage.rtmap.client.map;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;

/**
 * Map tiles currently held in memory, backed by {@link MapStorage}.
 *
 * <p>Level 0 holds full-detail regions. Level {@code n} holds overview tiles that cover {@code 2^n} regions per
 * side at one pixel per {@code 2^n} blocks, so zoomed-out views only ever need a handful of tiles. Overview tiles
 * are refreshed whenever a region is saved.
 *
 * <p>At most {@value #MAX_LOADED} tiles stay loaded; the least recently used one is saved and released when
 * that is exceeded.
 */
public final class MapCache {
	/** The coarsest overview level: one pixel per 128 blocks, enough to zoom out to 0.01x. */
	public static final int MAX_LEVEL = 7;

	private static final int MAX_LOADED = 128;
	private static final int SAVE_INTERVAL_TICKS = 100;
	/** Tiles read from disk per frame while drawing, so panning over a big map does not stall. */
	private static final int MAX_DISPLAY_LOADS_PER_FRAME = 2;
	/** Regions folded into the overview tiles per tick when an older map has to be converted. */
	private static final int OVERVIEW_REBUILDS_PER_TICK = 2;

	private static final class DimensionData {
		/** Loaded tiles, indexed by level. */
		final List<Map<Long, MapRegion>> loaded = new ArrayList<>();
		/** Tile keys that exist on disk, per level. */
		final Map<Integer, Set<Long>> onDisk;
		/** Regions still to be folded into the overview tiles. */
		final Deque<Long> overviewRebuild = new ArrayDeque<>();

		DimensionData(Map<Integer, Set<Long>> onDisk) {
			this.onDisk = onDisk;
			for (int level = 0; level <= MAX_LEVEL; level++) {
				loaded.add(new HashMap<>());
			}
		}
	}

	private record Entry(RegistryKey<World> dimension, DimensionData data, int level, long key, MapRegion region) {
	}

	private static final Map<RegistryKey<World>, DimensionData> DIMENSIONS = new HashMap<>();
	private static long clock;
	private static int loadedCount;
	private static int displayLoads;
	private static int ticks;

	private MapCache() {
	}

	private static DimensionData data(RegistryKey<World> dimension) {
		DimensionData data = DIMENSIONS.get(dimension);
		if (data == null) {
			data = new DimensionData(MapStorage.listTiles(dimension, MAX_LEVEL));
			DIMENSIONS.put(dimension, data);

			// Maps saved before overview tiles existed (or an interrupted conversion) get rebuilt in the background.
			if (!MapStorage.hasOverviewMarker(dimension)) {
				data.overviewRebuild.addAll(data.onDisk.get(0));
				if (data.overviewRebuild.isEmpty()) {
					MapStorage.writeOverviewMarker(dimension);
				}
			}
		}
		return data;
	}

	/** The region to draw chunks into: loaded from disk if it was saved before, otherwise new. */
	public static MapRegion regionForWrite(RegistryKey<World> dimension, int regionX, int regionZ) {
		return getOrOpen(dimension, data(dimension), 0, regionX, regionZ);
	}

	/** Call once per frame before {@link #tileForDisplay}. */
	public static void beginFrame() {
		displayLoads = 0;
	}

	/** A tile for drawing, or null if it is empty or not loaded yet. */
	public static MapRegion tileForDisplay(RegistryKey<World> dimension, int level, int x, int z) {
		DimensionData data = data(dimension);
		long key = ChunkPos.toLong(x, z);
		MapRegion region = data.loaded.get(level).get(key);
		if (region == null) {
			if (!data.onDisk.get(level).contains(key) || displayLoads >= MAX_DISPLAY_LOADS_PER_FRAME) {
				return null;
			}
			displayLoads++;
			region = getOrOpen(dimension, data, level, x, z);
		}
		region.touch(++clock);
		return region;
	}

	private static MapRegion getOrOpen(RegistryKey<World> dimension, DimensionData data, int level, int x, int z) {
		long key = ChunkPos.toLong(x, z);
		MapRegion region = data.loaded.get(level).get(key);
		if (region == null) {
			region = new MapRegion();
			if (data.onDisk.get(level).contains(key)) {
				int[] pixels = MapStorage.read(dimension, level, x, z);
				if (pixels != null) {
					region.load(pixels);
				}
			}
			data.loaded.get(level).put(key, region);
			loadedCount++;
			region.touch(++clock);
			evictIfNeeded(region);
		}
		region.touch(++clock);
		return region;
	}

	private static void evictIfNeeded(MapRegion keep) {
		while (loadedCount > MAX_LOADED) {
			Entry oldest = null;
			for (Map.Entry<RegistryKey<World>, DimensionData> dimension : DIMENSIONS.entrySet()) {
				DimensionData data = dimension.getValue();
				for (int level = 0; level <= MAX_LEVEL; level++) {
					for (Map.Entry<Long, MapRegion> entry : data.loaded.get(level).entrySet()) {
						MapRegion region = entry.getValue();
						if (region != keep && (oldest == null || region.lastUsed() < oldest.region().lastUsed())) {
							oldest = new Entry(dimension.getKey(), data, level, entry.getKey(), region);
						}
					}
				}
			}
			if (oldest == null) {
				return;
			}
			oldest.data().loaded.get(oldest.level()).remove(oldest.key());
			save(oldest);
			oldest.region().close();
			loadedCount--;
		}
	}

	private static void save(Entry entry) {
		MapRegion region = entry.region();
		if (region.isClosed() || !region.isUnsaved()) {
			return;
		}
		int x = ChunkPos.getPackedX(entry.key());
		int z = ChunkPos.getPackedZ(entry.key());
		int[] pixels = region.snapshot();
		MapStorage.write(entry.dimension(), entry.level(), x, z, pixels);
		region.markSaved();
		entry.data().onDisk.get(entry.level()).add(entry.key());

		if (entry.level() == 0) {
			updateOverviews(entry.dimension(), entry.data(), x, z, pixels);
		}
	}

	/** Folds a region's pixels into the overview tiles above it. {@code pixels} is only read. */
	private static void updateOverviews(RegistryKey<World> dimension, DimensionData data, int regionX, int regionZ, int[] pixels) {
		int[] current = pixels;
		int size = MapRegion.SIZE;
		for (int level = 1; level <= MAX_LEVEL; level++) {
			current = halve(current, size);
			size /= 2;

			// Arithmetic shifts round toward negative infinity, which is what negative coordinates need.
			int tileX = regionX >> level;
			int tileZ = regionZ >> level;
			int offsetX = (regionX - (tileX << level)) * size;
			int offsetZ = (regionZ - (tileZ << level)) * size;

			MapRegion tile = getOrOpen(dimension, data, level, tileX, tileZ);
			for (int z = 0; z < size; z++) {
				for (int x = 0; x < size; x++) {
					tile.setPixel(offsetX + x, offsetZ + z, current[z * size + x]);
				}
			}
		}
	}

	/** Halves an image by averaging each 2x2 block, ignoring empty (transparent) pixels. */
	private static int[] halve(int[] source, int size) {
		int half = size / 2;
		int[] result = new int[half * half];
		for (int z = 0; z < half; z++) {
			for (int x = 0; x < half; x++) {
				int i = (z * 2) * size + x * 2;
				result[z * half + x] = average(source[i], source[i + 1], source[i + size], source[i + size + 1]);
			}
		}
		return result;
	}

	private static int average(int a, int b, int c, int d) {
		int count = opaque(a) + opaque(b) + opaque(c) + opaque(d);
		if (count == 0) {
			return 0;
		}
		int red = channel(a, 0) + channel(b, 0) + channel(c, 0) + channel(d, 0);
		int green = channel(a, 8) + channel(b, 8) + channel(c, 8) + channel(d, 8);
		int blue = channel(a, 16) + channel(b, 16) + channel(c, 16) + channel(d, 16);
		// Pixels are ABGR; map colors are always fully opaque.
		return 0xFF000000 | ((blue / count) << 16) | ((green / count) << 8) | (red / count);
	}

	private static int opaque(int color) {
		return (color >>> 24) == 0 ? 0 : 1;
	}

	/** A color channel, or 0 for an empty pixel so it does not count towards the average. */
	private static int channel(int color, int shift) {
		return (color >>> 24) == 0 ? 0 : (color >> shift) & 0xFF;
	}

	/** Queues every tile with unsaved changes for writing. */
	public static void saveAll() {
		// Saving a region can open overview tiles and evict others, so work from a copy.
		List<Entry> entries = new ArrayList<>();
		for (Map.Entry<RegistryKey<World>, DimensionData> dimension : DIMENSIONS.entrySet()) {
			DimensionData data = dimension.getValue();
			for (int level = 0; level <= MAX_LEVEL; level++) {
				for (Map.Entry<Long, MapRegion> entry : data.loaded.get(level).entrySet()) {
					entries.add(new Entry(dimension.getKey(), data, level, entry.getKey(), entry.getValue()));
				}
			}
		}
		// Regions first: saving them is what refreshes the overview tiles that get saved afterwards.
		entries.sort((left, right) -> Integer.compare(left.level(), right.level()));
		for (Entry entry : entries) {
			save(entry);
		}
		// Tiles created by the pass above were not in the copy.
		for (Map.Entry<RegistryKey<World>, DimensionData> dimension : DIMENSIONS.entrySet()) {
			DimensionData data = dimension.getValue();
			for (int level = 1; level <= MAX_LEVEL; level++) {
				for (Map.Entry<Long, MapRegion> entry : new ArrayList<>(data.loaded.get(level).entrySet())) {
					save(new Entry(dimension.getKey(), data, level, entry.getKey(), entry.getValue()));
				}
			}
		}
	}

	/** Periodic autosave and background conversion of maps that have no overview tiles yet. */
	public static void tick() {
		if (++ticks % SAVE_INTERVAL_TICKS == 0) {
			saveAll();
		}
		rebuildOverviews();
	}

	private static void rebuildOverviews() {
		for (Map.Entry<RegistryKey<World>, DimensionData> dimension : new ArrayList<>(DIMENSIONS.entrySet())) {
			DimensionData data = dimension.getValue();
			for (int done = 0; done < OVERVIEW_REBUILDS_PER_TICK && !data.overviewRebuild.isEmpty(); done++) {
				long key = data.overviewRebuild.poll();
				int x = ChunkPos.getPackedX(key);
				int z = ChunkPos.getPackedZ(key);
				// A region that is loaded will refresh the overviews itself when it is saved.
				if (!data.loaded.get(0).containsKey(key)) {
					int[] pixels = MapStorage.read(dimension.getKey(), 0, x, z);
					if (pixels != null) {
						updateOverviews(dimension.getKey(), data, x, z, pixels);
					}
				}
				if (data.overviewRebuild.isEmpty()) {
					// Write the tiles first; the marker is queued behind them, so it never claims more than exists.
					saveAll();
					MapStorage.writeOverviewMarker(dimension.getKey());
				}
			}
		}
	}

	/** Saves unsaved tiles, then frees every texture. Must run on the render thread. */
	public static void clear() {
		saveAll();
		for (DimensionData data : DIMENSIONS.values()) {
			for (Map<Long, MapRegion> level : data.loaded) {
				level.values().forEach(MapRegion::close);
			}
		}
		DIMENSIONS.clear();
		loadedCount = 0;
		ticks = 0;
	}
}
