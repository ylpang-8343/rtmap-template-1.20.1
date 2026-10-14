package com.cabbage.rtmap.server;

import com.cabbage.rtmap.chunk.ChunkSnapshot;
import com.cabbage.rtmap.mixin.ChunkTicketManagerAccessor;
import com.cabbage.rtmap.mixin.ThreadedAnvilChunkStorageAccessor;
import com.cabbage.rtmap.network.RTMapChannels;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ChunkLevelType;
import net.minecraft.server.world.ChunkTicket;
import net.minecraft.server.world.ChunkTicketManager;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.util.collection.SortedArraySet;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Reads how a rectangle of chunks is loaded. Must run on the server thread: the chunk maps and the ticket maps it
 * looks at are not safe to read from anywhere else. Nothing here changes any game state.
 */
public final class ChunkDataCollector {
	private ChunkDataCollector() {
	}

	public static ChunkSnapshot collect(ServerWorld world, int minX, int minZ, int width, int height, int what) {
		int area = width * height;
		boolean wantState = (what & RTMapChannels.QUERY_STATE) != 0;
		boolean wantLoad = (what & RTMapChannels.QUERY_LOAD) != 0;

		byte[] state = wantState ? new byte[area] : null;
		char[] entities = wantLoad ? new char[area] : null;
		char[] blockEntities = wantLoad ? new char[area] : null;
		char[] hoppers = wantLoad ? new char[area] : null;

		ThreadedAnvilChunkStorage storage = world.getChunkManager().threadedAnvilChunkStorage;
		ThreadedAnvilChunkStorageAccessor holders = (ThreadedAnvilChunkStorageAccessor) storage;
		ChunkTicketManager tickets = storage.getTicketManager();
		Long2ObjectOpenHashMap<SortedArraySet<ChunkTicket<?>>> ticketsByPosition =
			((ChunkTicketManagerAccessor) tickets).rtmap$getTicketsByPosition();

		for (int dz = 0; dz < height; dz++) {
			for (int dx = 0; dx < width; dx++) {
				long pos = ChunkPos.toLong(minX + dx, minZ + dz);
				ChunkHolder holder = holders.rtmap$getChunkHolder(pos);
				if (holder == null) {
					continue;
				}
				int level = levelOf(holder.getLevelType(), tickets, pos);
				if (level == ChunkSnapshot.LEVEL_NONE) {
					continue;
				}
				int index = dz * width + dx;
				if (state != null) {
					state[index] = (byte) (level | ticketBits(ticketsByPosition.get(pos)));
				}
				if (wantLoad) {
					countBlockEntities(holder, index, blockEntities, hoppers);
				}
			}
		}

		if (wantLoad) {
			for (Entity entity : world.iterateEntities()) {
				int dx = (entity.getBlockX() >> 4) - minX;
				int dz = (entity.getBlockZ() >> 4) - minZ;
				if (dx >= 0 && dx < width && dz >= 0 && dz < height) {
					int index = dz * width + dx;
					if (entities[index] < ChunkSnapshot.MAX_COUNT) {
						entities[index]++;
					}
				}
			}
		}

		return new ChunkSnapshot(world.getRegistryKey().getValue().toString(), minX, minZ, width, height,
			state, entities, blockEntities, hoppers);
	}

	private static int levelOf(ChunkLevelType type, ChunkTicketManager tickets, long pos) {
		switch (type) {
			case FULL:
				return ChunkSnapshot.LEVEL_BORDER;
			case BLOCK_TICKING:
				return ChunkSnapshot.LEVEL_BLOCK_TICKING;
			case ENTITY_TICKING:
				// A chunk can sit at the entity ticking level while no player is close enough to make its entities run.
				return tickets.shouldTickEntities(pos) ? ChunkSnapshot.LEVEL_ENTITY_TICKING : ChunkSnapshot.LEVEL_BLOCK_TICKING;
			default:
				return ChunkSnapshot.LEVEL_NONE;
		}
	}

	private static int ticketBits(SortedArraySet<ChunkTicket<?>> held) {
		if (held == null) {
			return 0;
		}
		int bits = 0;
		for (ChunkTicket<?> ticket : held) {
			ChunkTicketType<?> type = ticket.getType();
			if (type == ChunkTicketType.PLAYER) {
				bits |= ChunkSnapshot.TICKET_PLAYER;
			} else if (type == ChunkTicketType.PORTAL) {
				bits |= ChunkSnapshot.TICKET_PORTAL;
			} else if (type == ChunkTicketType.FORCED) {
				bits |= ChunkSnapshot.TICKET_FORCED;
			} else if (type == ChunkTicketType.START) {
				bits |= ChunkSnapshot.TICKET_START;
			} else if (type == ChunkTicketType.DRAGON) {
				bits |= ChunkSnapshot.TICKET_DRAGON;
			} else {
				bits |= ChunkSnapshot.TICKET_OTHER;
			}
		}
		return bits;
	}

	private static void countBlockEntities(ChunkHolder holder, int index, char[] blockEntities, char[] hoppers) {
		WorldChunk chunk = holder.getWorldChunk();
		if (chunk == null) {
			return;
		}
		int total = 0;
		int hopperCount = 0;
		for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
			total++;
			if (blockEntity instanceof HopperBlockEntity) {
				hopperCount++;
			}
		}
		blockEntities[index] = (char) Math.min(total, ChunkSnapshot.MAX_COUNT);
		hoppers[index] = (char) Math.min(hopperCount, ChunkSnapshot.MAX_COUNT);
	}
}
