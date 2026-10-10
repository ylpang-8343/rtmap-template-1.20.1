package com.cabbage.rtmap.client.portal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.cabbage.rtmap.client.waypoint.Waypoints;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Looks for nether portals in chunks as they load, in every dimension, and keeps {@link PortalStore} up to date.
 * Chunks are checked a few per tick, and the cheap palette test skips every section that has no portal block, so
 * this costs almost nothing for chunks without portals.
 */
public final class PortalScanner {
	private static final int CHUNKS_PER_TICK = 2;
	/** A portal frame is at most 21x21, so a connected group bigger than this is not a real portal. */
	private static final int MAX_PORTAL_BLOCKS = 500;

	private static final Set<ChunkPos> PENDING = new LinkedHashSet<>();
	private static ClientWorld pendingWorld;

	private PortalScanner() {
	}

	public static void init() {
		ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> queue(world, chunk.getPos()));
		ClientTickEvents.END_CLIENT_TICK.register(PortalScanner::tick);
	}

	public static void queue(ClientWorld world, ChunkPos pos) {
		if (world != pendingWorld) {
			PENDING.clear();
			pendingWorld = world;
		}
		PENDING.add(pos);
	}

	public static void reset() {
		PENDING.clear();
		pendingWorld = null;
	}

	private static void tick(MinecraftClient client) {
		ClientWorld world = client.world;
		if (world == null || !PortalStore.isLoaded()) {
			return;
		}
		if (world != pendingWorld) {
			PENDING.clear();
			pendingWorld = world;
		}

		int processed = 0;
		Iterator<ChunkPos> iterator = PENDING.iterator();
		while (iterator.hasNext() && processed < CHUNKS_PER_TICK) {
			ChunkPos pos = iterator.next();
			iterator.remove();
			WorldChunk chunk = world.getChunkManager().getWorldChunk(pos.x, pos.z);
			if (chunk != null) {
				scan(world, chunk);
				processed++;
			}
		}
	}

	private static void scan(ClientWorld world, WorldChunk chunk) {
		String dimension = Waypoints.dimensionId(world);
		forgetDestroyed(world, chunk, dimension);

		List<BlockPos> found = findPortalBlocks(chunk);
		if (found.isEmpty()) {
			return;
		}

		// Portal blocks that belong together form one portal; it may reach into a neighbouring chunk.
		Set<Long> visited = new HashSet<>();
		for (BlockPos start : found) {
			if (visited.contains(start.asLong())) {
				continue;
			}
			List<BlockPos> portal = floodFill(world, start, visited);
			if (portal.size() > MAX_PORTAL_BLOCKS) {
				continue;
			}
			PortalStore.add(toPortal(dimension, portal));
		}
	}

	/** Drops portals in this chunk whose recorded block is no longer a portal (they were broken). */
	private static void forgetDestroyed(ClientWorld world, WorldChunk chunk, String dimension) {
		ChunkPos pos = chunk.getPos();
		List<Portal> gone = null;
		for (Portal portal : PortalStore.portals()) {
			if (portal.dimension().equals(dimension) && (portal.anchorX() >> 4) == pos.x && (portal.anchorZ() >> 4) == pos.z
				&& !world.getBlockState(new BlockPos(portal.anchorX(), portal.anchorY(), portal.anchorZ())).isOf(Blocks.NETHER_PORTAL)) {
				if (gone == null) {
					gone = new ArrayList<>();
				}
				gone.add(portal);
			}
		}
		if (gone != null) {
			gone.forEach(PortalStore::remove);
		}
	}

	private static List<BlockPos> findPortalBlocks(WorldChunk chunk) {
		List<BlockPos> found = new ArrayList<>();
		ChunkPos chunkPos = chunk.getPos();
		ChunkSection[] sections = chunk.getSectionArray();
		for (int index = 0; index < sections.length; index++) {
			ChunkSection section = sections[index];
			// The palette test answers "is there any portal block in here" without looking at each block.
			if (section == null || section.isEmpty() || !section.hasAny(state -> state.isOf(Blocks.NETHER_PORTAL))) {
				continue;
			}
			int baseY = chunk.sectionIndexToCoord(index) * 16;
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						if (section.getBlockState(x, y, z).isOf(Blocks.NETHER_PORTAL)) {
							found.add(new BlockPos(chunkPos.getStartX() + x, baseY + y, chunkPos.getStartZ() + z));
						}
					}
				}
			}
		}
		return found;
	}

	/** All portal blocks connected to {@code start}, looking only at chunks that are loaded. */
	private static List<BlockPos> floodFill(ClientWorld world, BlockPos start, Set<Long> visited) {
		List<BlockPos> group = new ArrayList<>();
		Deque<BlockPos> open = new ArrayDeque<>();
		open.add(start);
		visited.add(start.asLong());
		while (!open.isEmpty() && group.size() <= MAX_PORTAL_BLOCKS) {
			BlockPos current = open.poll();
			group.add(current);
			for (net.minecraft.util.math.Direction direction : net.minecraft.util.math.Direction.values()) {
				BlockPos next = current.offset(direction);
				if (visited.contains(next.asLong())) {
					continue;
				}
				if (world.getChunkManager().getWorldChunk(next.getX() >> 4, next.getZ() >> 4) != null
					&& world.getBlockState(next).isOf(Blocks.NETHER_PORTAL)) {
					visited.add(next.asLong());
					open.add(next);
				}
			}
		}
		return group;
	}

	private static Portal toPortal(String dimension, List<BlockPos> blocks) {
		long sumX = 0;
		long sumY = 0;
		long sumZ = 0;
		BlockPos anchor = blocks.get(0);
		for (BlockPos block : blocks) {
			sumX += block.getX();
			sumY += block.getY();
			sumZ += block.getZ();
			// The lowest block is a stable choice, however the group was found.
			if (block.getY() < anchor.getY() || (block.getY() == anchor.getY()
				&& (block.getX() < anchor.getX() || (block.getX() == anchor.getX() && block.getZ() < anchor.getZ())))) {
				anchor = block;
			}
		}
		int count = blocks.size();
		return new Portal(dimension, (int) Math.floorDiv(sumX, count), (int) Math.floorDiv(sumY, count),
			(int) Math.floorDiv(sumZ, count), anchor.getX(), anchor.getY(), anchor.getZ());
	}
}
