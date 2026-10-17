package com.cabbage.rtmap.chunk;

import java.util.ArrayList;
import java.util.List;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.math.ChunkPos;

/**
 * What one profiling run measured: for every dimension, how much server time each chunk used, split by what the
 * time was spent on. Times are totals over the whole run in microseconds; divide by {@link #ticks()} for a per-tick
 * figure. Built by the server and read by the client; in singleplayer it is handed over without being serialised.
 */
public final class ChunkTimingResult {
	/** What a measured stretch of time was spent on. */
	public static final int ENTITY = 0;
	public static final int BLOCK_ENTITY = 1;
	/** Scheduled block and fluid ticks: redstone, observers, flowing water and so on. */
	public static final int SCHEDULED_TICK = 2;
	/** Random ticks of a chunk's blocks, and its weather effects. */
	public static final int RANDOM_TICK = 3;
	public static final int SPAWNING = 4;
	/** Block events: piston pushes and pulls, note blocks, chests opening. */
	public static final int BLOCK_EVENT = 5;
	public static final int CATEGORIES = 6;
	public static final String[] CATEGORY_KEYS = {"entity", "block_entity", "scheduled_tick", "random_tick", "spawning", "block_event"};

	public static final int MAX_DIMENSIONS = 8;
	/** At most this many chunks per dimension are kept, the most expensive first. */
	public static final int MAX_ENTRIES = 2048;

	private static final int MAX_DIMENSION_LENGTH = 128;

	/** The chunks of one dimension that used server time. */
	public static final class Dimension {
		private final String id;
		private final long[] categoryTotals;
		private final int[] chunkX;
		private final int[] chunkZ;
		/** {@code size * CATEGORIES} values: entry i's time in category c is at {@code i * CATEGORIES + c}. */
		private final int[] micros;
		private final Long2IntOpenHashMap index = new Long2IntOpenHashMap();

		public Dimension(String id, long[] categoryTotals, int[] chunkX, int[] chunkZ, int[] micros) {
			this.id = id;
			this.categoryTotals = categoryTotals;
			this.chunkX = chunkX;
			this.chunkZ = chunkZ;
			this.micros = micros;
			index.defaultReturnValue(-1);
			for (int i = 0; i < chunkX.length; i++) {
				index.put(ChunkPos.toLong(chunkX[i], chunkZ[i]), i);
			}
		}

		public String id() {
			return id;
		}

		public int size() {
			return chunkX.length;
		}

		public int chunkX(int entry) {
			return chunkX[entry];
		}

		public int chunkZ(int entry) {
			return chunkZ[entry];
		}

		/** Time of one entry in one category, in microseconds over the whole run. */
		public int micros(int entry, int category) {
			return micros[entry * CATEGORIES + category];
		}

		public long totalMicros(int entry) {
			long total = 0;
			for (int category = 0; category < CATEGORIES; category++) {
				total += micros(entry, category);
			}
			return total;
		}

		/** All chunks of the dimension together, per category, in microseconds. Includes chunks that were cut from the list. */
		public long categoryTotal(int category) {
			return categoryTotals[category];
		}

		public long totalMicros() {
			long total = 0;
			for (long value : categoryTotals) {
				total += value;
			}
			return total;
		}

		/** The entry for a chunk, or -1 if it is not in the list. */
		public int find(int x, int z) {
			return index.get(ChunkPos.toLong(x, z));
		}
	}

	private final int ticks;
	private final long tickMicros;
	private final List<Dimension> dimensions;

	public ChunkTimingResult(int ticks, long tickMicros, List<Dimension> dimensions) {
		this.ticks = ticks;
		this.tickMicros = tickMicros;
		this.dimensions = dimensions;
	}

	/** How many server ticks were measured. */
	public int ticks() {
		return ticks;
	}

	/** The server's own time for those ticks, whole, in microseconds. What the measured parts are compared with. */
	public long tickMicros() {
		return tickMicros;
	}

	public List<Dimension> dimensions() {
		return dimensions;
	}

	public Dimension dimension(String id) {
		for (Dimension dimension : dimensions) {
			if (dimension.id().equals(id)) {
				return dimension;
			}
		}
		return null;
	}

	/** All measured time in every dimension, in microseconds. */
	public long measuredMicros() {
		long total = 0;
		for (Dimension dimension : dimensions) {
			total += dimension.totalMicros();
		}
		return total;
	}

	public void write(PacketByteBuf buf) {
		buf.writeVarInt(ticks);
		buf.writeVarLong(tickMicros);
		buf.writeVarInt(dimensions.size());
		for (Dimension dimension : dimensions) {
			buf.writeString(dimension.id, MAX_DIMENSION_LENGTH);
			for (long total : dimension.categoryTotals) {
				buf.writeVarLong(total);
			}
			buf.writeVarInt(dimension.size());
			for (int i = 0; i < dimension.size(); i++) {
				buf.writeInt(dimension.chunkX[i]);
				buf.writeInt(dimension.chunkZ[i]);
				for (int category = 0; category < CATEGORIES; category++) {
					buf.writeVarInt(dimension.micros(i, category));
				}
			}
		}
	}

	/** Reads a result, throwing if a count is outside what {@link #write} can produce. */
	public static ChunkTimingResult read(PacketByteBuf buf) {
		int ticks = buf.readVarInt();
		long tickMicros = buf.readVarLong();
		int count = buf.readVarInt();
		if (ticks < 0 || tickMicros < 0 || count < 0 || count > MAX_DIMENSIONS) {
			throw new IllegalArgumentException("bad timing header");
		}
		List<Dimension> dimensions = new ArrayList<>(count);
		for (int d = 0; d < count; d++) {
			String id = buf.readString(MAX_DIMENSION_LENGTH);
			long[] totals = new long[CATEGORIES];
			for (int category = 0; category < CATEGORIES; category++) {
				totals[category] = Math.max(0, buf.readVarLong());
			}
			int size = buf.readVarInt();
			if (size < 0 || size > MAX_ENTRIES) {
				throw new IllegalArgumentException("bad timing entry count " + size);
			}
			int[] chunkX = new int[size];
			int[] chunkZ = new int[size];
			int[] micros = new int[size * CATEGORIES];
			for (int i = 0; i < size; i++) {
				chunkX[i] = buf.readInt();
				chunkZ[i] = buf.readInt();
				for (int category = 0; category < CATEGORIES; category++) {
					micros[i * CATEGORIES + category] = Math.max(0, buf.readVarInt());
				}
			}
			dimensions.add(new Dimension(id, totals, chunkX, chunkZ, micros));
		}
		return new ChunkTimingResult(ticks, tickMicros, dimensions);
	}
}
