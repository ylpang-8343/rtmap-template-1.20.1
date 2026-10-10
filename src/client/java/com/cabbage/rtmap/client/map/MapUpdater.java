package com.cabbage.rtmap.client.map;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

import com.cabbage.rtmap.client.network.ManualSeed;
import com.cabbage.rtmap.client.portal.PortalScanner;
import com.cabbage.rtmap.client.portal.PortalStore;
import com.cabbage.rtmap.client.waypoint.WaypointStore;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Decides which chunks get (re)drawn onto the map. Sampling runs on the client thread with a small
 * per-tick budget so it never causes a visible hitch.
 */
public final class MapUpdater {
	/** Chunks sampled per tick. */
	private static final int BUDGET = 4;
	/** Nearby chunks are resampled this often, so block changes near the player show up. */
	private static final int REFRESH_INTERVAL_TICKS = 20;
	private static final int REFRESH_RADIUS = 2;

	private static final Set<ChunkPos> PENDING = new LinkedHashSet<>();
	private static ClientWorld pendingWorld;
	private static ClientPlayNetworkHandler trackedConnection;
	private static boolean storageReady;
	private static int ticks;

	private MapUpdater() {
	}

	public static void queue(ClientWorld world, ChunkPos pos) {
		if (world != pendingWorld) {
			PENDING.clear();
			pendingWorld = world;
		}
		PENDING.add(pos);
	}

	/**
	 * Ends the current map session: unsaved regions are written to the world they belong to, then everything
	 * is forgotten. Safe to call more than once.
	 */
	public static void endSession() {
		PENDING.clear();
		pendingWorld = null;
		storageReady = false;
		ticks = 0;
		trackedConnection = null;
		// Both must be flushed while the storage path still points at their world.
		PortalStore.saveIfDirty();
		PortalStore.clear();
		PortalScanner.reset();
		WaypointStore.saveIfDirty();
		WaypointStore.clear();
		ManualSeed.unload();
		MapCache.clear();
		MapStorage.reset();
	}

	public static void tick(MinecraftClient client) {
		ClientWorld world = client.world;
		if (world == null || client.player == null) {
			return;
		}

		// A new connection means a different world, whatever order the disconnect events arrived in.
		// (Changing dimension keeps the same connection, so the map is kept.)
		ClientPlayNetworkHandler connection = client.getNetworkHandler();
		if (connection != trackedConnection) {
			endSession();
			trackedConnection = connection;
		}
		if (world != pendingWorld) {
			PENDING.clear();
			pendingWorld = world;
		}

		// Nothing can be saved or loaded until we know which world this is.
		if (!MapStorage.tick(client)) {
			return;
		}
		if (!storageReady) {
			storageReady = true;
			PortalStore.load(MapStorage.currentWorldDir());
			WaypointStore.load(MapStorage.currentWorldDir());
			ManualSeed.load(MapStorage.currentWorldDir());
			queueLoadedChunksAround(client, world);
		}
		MapCache.tick();

		// Nether-like dimensions have a roof, so a plain surface view would just show bedrock.
		if (world.getDimension().hasCeiling()) {
			PENDING.clear();
			return;
		}

		if (++ticks % REFRESH_INTERVAL_TICKS == 0) {
			ChunkPos center = client.player.getChunkPos();
			for (int dx = -REFRESH_RADIUS; dx <= REFRESH_RADIUS; dx++) {
				for (int dz = -REFRESH_RADIUS; dz <= REFRESH_RADIUS; dz++) {
					PENDING.add(new ChunkPos(center.x + dx, center.z + dz));
				}
			}
		}

		int processed = 0;
		Iterator<ChunkPos> iterator = PENDING.iterator();
		while (iterator.hasNext() && processed < BUDGET) {
			ChunkPos pos = iterator.next();
			iterator.remove();

			WorldChunk chunk = world.getChunkManager().getWorldChunk(pos.x, pos.z);
			if (chunk == null) {
				continue; // not loaded (any more)
			}
			int regionX = Math.floorDiv(pos.getStartX(), MapRegion.SIZE);
			int regionZ = Math.floorDiv(pos.getStartZ(), MapRegion.SIZE);
			ChunkSampler.sample(world, chunk, MapCache.regionForWrite(world.getRegistryKey(), regionX, regionZ));
			processed++;
		}
	}

	/** Chunks that loaded while the world was still being identified were skipped, so pick them up now. */
	private static void queueLoadedChunksAround(MinecraftClient client, ClientWorld world) {
		int radius = client.options.getClampedViewDistance();
		ChunkPos center = client.player.getChunkPos();
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				if (world.getChunkManager().getWorldChunk(center.x + dx, center.z + dz) != null) {
					PENDING.add(new ChunkPos(center.x + dx, center.z + dz));
					// Chunks that loaded before the world was identified were not scanned for portals either.
					PortalScanner.queue(world, new ChunkPos(center.x + dx, center.z + dz));
				}
			}
		}
	}
}
