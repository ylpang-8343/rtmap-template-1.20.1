package com.cabbage.rtmap.server;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import com.cabbage.rtmap.chunk.ChunkTimingResult;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;

/**
 * Measures which chunks use the server's tick time. It only does anything while a run is going: the game's own
 * methods for ticking an entity, a block entity, a chunk and so on are timed (see the timing mixins), and the time is
 * added up per chunk. When no run is going the only cost is one volatile read per timed call.
 *
 * <p>Everything here runs on the server thread. A run starts at the beginning of a tick and ends at the end of one,
 * so a timed call never sees a run begin or end half way through it.
 */
public final class ChunkProfiler {
	public static final int MIN_SECONDS = 5;
	public static final int MAX_SECONDS = 60;
	private static final int TICKS_PER_SECOND = 20;
	/** Timed calls nest (a spawn inside a chunk tick, say); deeper than this is not timed. */
	private static final int MAX_DEPTH = 8;
	private static final double NANOS_PER_MICRO = 1000.0;

	public enum Outcome {
		STARTED, BUSY
	}

	private static final class Session {
		final UUID owner;
		final int totalTicks;
		final Consumer<ChunkTimingResult> onFinished;
		final Map<RegistryKey<World>, Long2ObjectOpenHashMap<long[]>> perDimension = new HashMap<>();
		int ticks;
		long tickStartNanos;
		long tickNanos;
		boolean stopRequested;
		RegistryKey<World> lastKey;
		Long2ObjectOpenHashMap<long[]> lastMap;

		Session(UUID owner, int totalTicks, Consumer<ChunkTimingResult> onFinished) {
			this.owner = owner;
			this.totalTicks = totalTicks;
			this.onFinished = onFinished;
		}

		long[] slot(World world, int chunkX, int chunkZ) {
			RegistryKey<World> key = world.getRegistryKey();
			if (key != lastKey) {
				lastMap = perDimension.computeIfAbsent(key, k -> new Long2ObjectOpenHashMap<>());
				lastKey = key;
			}
			return lastMap.computeIfAbsent(ChunkPos.toLong(chunkX, chunkZ), pos -> new long[ChunkTimingResult.CATEGORIES]);
		}
	}

	/** Read by the timing mixins on every timed call; only changed at tick boundaries on the server thread. */
	private static volatile boolean running;
	private static Session session;
	private static Session pending;
	private static Thread serverThread;

	private static final long[] START_NANOS = new long[MAX_DEPTH];
	private static final long[] CHILD_NANOS = new long[MAX_DEPTH];
	private static int depth;

	private ChunkProfiler() {
	}

	public static void init() {
		ServerTickEvents.START_SERVER_TICK.register(ChunkProfiler::onTickStart);
		ServerTickEvents.END_SERVER_TICK.register(ChunkProfiler::onTickEnd);
	}

	/** Whether a run is going. */
	public static boolean isRunning() {
		return running;
	}

	/** The player whose run is going or about to start, or null. */
	public static UUID owner() {
		Session current = session != null ? session : pending;
		return current == null ? null : current.owner;
	}

	/**
	 * Asks for a run. It starts at the next tick and calls {@code onFinished} on the server thread when it is done.
	 * Only one run can go at a time. Call on the server thread.
	 */
	public static Outcome start(UUID owner, int seconds, Consumer<ChunkTimingResult> onFinished) {
		if (session != null || pending != null) {
			return Outcome.BUSY;
		}
		int clamped = Math.max(MIN_SECONDS, Math.min(MAX_SECONDS, seconds));
		pending = new Session(owner, clamped * TICKS_PER_SECOND, onFinished);
		return Outcome.STARTED;
	}

	/** Ends the owner's run at the end of the current tick; the result is still delivered. */
	public static void stop(UUID owner) {
		if (session != null && session.owner.equals(owner)) {
			session.stopRequested = true;
		} else if (pending != null && pending.owner.equals(owner)) {
			pending = null;
		}
	}

	private static void onTickStart(MinecraftServer server) {
		if (pending != null && session == null) {
			session = pending;
			pending = null;
			serverThread = Thread.currentThread();
			depth = 0;
			running = true;
		}
		if (session != null) {
			session.tickStartNanos = System.nanoTime();
			depth = 0;
		}
	}

	private static void onTickEnd(MinecraftServer server) {
		Session current = session;
		if (current == null) {
			return;
		}
		current.ticks++;
		current.tickNanos += System.nanoTime() - current.tickStartNanos;
		if (current.ticks >= current.totalTicks || current.stopRequested) {
			running = false;
			session = null;
			depth = 0;
			current.onFinished.accept(build(current));
		}
	}

	/** Starts timing one call. Pair with {@link #end}. Costs a volatile read when no run is going. */
	public static void begin() {
		if (!running || Thread.currentThread() != serverThread) {
			return;
		}
		if (depth < MAX_DEPTH) {
			START_NANOS[depth] = System.nanoTime();
			CHILD_NANOS[depth] = 0;
		}
		depth++;
	}

	/** Stops timing the call started by the matching {@link #begin} and charges it to a chunk. */
	public static void end(World world, int chunkX, int chunkZ, int category) {
		if (!running || depth == 0 || Thread.currentThread() != serverThread) {
			return;
		}
		depth--;
		if (depth >= MAX_DEPTH) {
			return;
		}
		long elapsed = System.nanoTime() - START_NANOS[depth];
		// What a nested call took is the nested call's, not the outer one's, or the same time would be counted twice.
		if (depth > 0 && depth - 1 < MAX_DEPTH) {
			CHILD_NANOS[depth - 1] += elapsed;
		}
		if (world instanceof ServerWorld && session != null) {
			session.slot(world, chunkX, chunkZ)[category] += Math.max(0, elapsed - CHILD_NANOS[depth]);
		}
	}

	private static ChunkTimingResult build(Session finished) {
		List<ChunkTimingResult.Dimension> dimensions = new ArrayList<>();
		for (Map.Entry<RegistryKey<World>, Long2ObjectOpenHashMap<long[]>> entry : finished.perDimension.entrySet()) {
			if (dimensions.size() >= ChunkTimingResult.MAX_DIMENSIONS) {
				break;
			}
			dimensions.add(buildDimension(entry.getKey().getValue().toString(), entry.getValue()));
		}
		return new ChunkTimingResult(finished.ticks, Math.round(finished.tickNanos / NANOS_PER_MICRO), dimensions);
	}

	private static ChunkTimingResult.Dimension buildDimension(String id, Long2ObjectOpenHashMap<long[]> chunks) {
		long[] categoryTotals = new long[ChunkTimingResult.CATEGORIES];
		List<Long2ObjectMap.Entry<long[]>> ranked = new ArrayList<>(chunks.long2ObjectEntrySet());
		for (Long2ObjectMap.Entry<long[]> entry : ranked) {
			for (int category = 0; category < ChunkTimingResult.CATEGORIES; category++) {
				categoryTotals[category] += Math.round(entry.getValue()[category] / NANOS_PER_MICRO);
			}
		}
		ranked.sort(Comparator.comparingLong((Long2ObjectMap.Entry<long[]> entry) -> sum(entry.getValue())).reversed());

		int size = Math.min(ranked.size(), ChunkTimingResult.MAX_ENTRIES);
		int[] chunkX = new int[size];
		int[] chunkZ = new int[size];
		int[] micros = new int[size * ChunkTimingResult.CATEGORIES];
		for (int i = 0; i < size; i++) {
			long pos = ranked.get(i).getLongKey();
			chunkX[i] = ChunkPos.getPackedX(pos);
			chunkZ[i] = ChunkPos.getPackedZ(pos);
			for (int category = 0; category < ChunkTimingResult.CATEGORIES; category++) {
				micros[i * ChunkTimingResult.CATEGORIES + category] =
					(int) Math.min(Integer.MAX_VALUE, Math.round(ranked.get(i).getValue()[category] / NANOS_PER_MICRO));
			}
		}
		return new ChunkTimingResult.Dimension(id, categoryTotals, chunkX, chunkZ, micros);
	}

	private static long sum(long[] values) {
		long total = 0;
		for (long value : values) {
			total += value;
		}
		return total;
	}
}
