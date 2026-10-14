package com.cabbage.rtmap.client.chunk;

import com.cabbage.rtmap.chunk.ChunkSnapshot;

/**
 * A rough "how much work might this chunk cause" number. It is built only from counts and from how far the chunk
 * is processed, so it is an estimate and not a measured tick time: a farm that is cheap per entity and a clock that
 * is expensive per block entity will not be told apart. The weights follow what actually runs in each state.
 */
public final class ChunkLoadScore {
	private ChunkLoadScore() {
	}

	public static double of(ChunkSnapshot snapshot, int chunkX, int chunkZ) {
		int level = snapshot.level(chunkX, chunkZ);
		double entity;
		double blockEntity;
		double hopper;
		switch (level) {
			case ChunkSnapshot.LEVEL_ENTITY_TICKING -> {
				entity = 1.0;
				blockEntity = 0.5;
				hopper = 2.0;
			}
			case ChunkSnapshot.LEVEL_BLOCK_TICKING -> {
				// Entities sit frozen here; block entities and hoppers still run.
				entity = 0.1;
				blockEntity = 0.5;
				hopper = 2.0;
			}
			case ChunkSnapshot.LEVEL_BORDER -> {
				entity = 0.02;
				blockEntity = 0.0;
				hopper = 0.0;
			}
			default -> {
				return 0.0;
			}
		}
		return snapshot.entities(chunkX, chunkZ) * entity
			+ snapshot.blockEntities(chunkX, chunkZ) * blockEntity
			+ snapshot.hoppers(chunkX, chunkZ) * hopper;
	}
}
