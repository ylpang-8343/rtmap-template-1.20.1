package com.cabbage.rtmap.world;

import net.minecraft.util.math.random.ChunkRandom;

/** Slime chunk lookup. Only meaningful in the overworld. */
public final class SlimeChunks {
	/** The salt vanilla mixes into the slime chunk random (see SlimeEntity). */
	private static final long SALT = 987234911L;

	private SlimeChunks() {
	}

	/** Uses vanilla's own random setup, so the result matches where slimes can actually spawn. */
	public static boolean isSlimeChunk(long worldSeed, int chunkX, int chunkZ) {
		return ChunkRandom.getSlimeRandom(chunkX, chunkZ, worldSeed, SALT).nextInt(10) == 0;
	}
}
