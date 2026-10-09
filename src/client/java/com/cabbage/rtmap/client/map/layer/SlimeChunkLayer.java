package com.cabbage.rtmap.client.map.layer;

import java.util.OptionalLong;

import com.cabbage.rtmap.client.network.ClientSession;
import com.cabbage.rtmap.world.SlimeChunks;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.world.World;

/**
 * Marks slime chunks in green. Needs the world seed (see {@link ClientSession}) and only exists in the overworld.
 * Hidden when zoomed out too far, where it would be thousands of chunks and each one smaller than a screen pixel.
 */
public final class SlimeChunkLayer implements MapLayer {
	/** Below this zoom (pixels per block) the layer is not drawn. */
	private static final double MIN_ZOOM = 0.25;
	/** Safety net against a pathological view size. */
	private static final int MAX_CHUNKS_IN_VIEW = 60_000;
	private static final int COLOR = 0x6055FF55;

	// Which chunks are slime chunks only changes when the visible chunk range or the seed does.
	private long cachedSeed;
	private int cachedFirstX;
	private int cachedLastX;
	private int cachedFirstZ;
	private int cachedLastZ;
	private int[] cachedChunks = new int[0];
	private int cachedCount;
	private boolean cacheValid;

	@Override
	public String id() {
		return "slime_chunks";
	}

	@Override
	public void render(DrawContext context, LayerView view) {
		OptionalLong seed = ClientSession.seed();
		if (seed.isEmpty() || view.world().getRegistryKey() != World.OVERWORLD || view.zoom() < MIN_ZOOM) {
			return;
		}

		int firstX = (int) Math.floor(view.leftBlock() / 16.0);
		int lastX = (int) Math.floor(view.rightBlock() / 16.0);
		int firstZ = (int) Math.floor(view.topBlock() / 16.0);
		int lastZ = (int) Math.floor(view.bottomBlock() / 16.0);
		if ((long) (lastX - firstX + 1) * (lastZ - firstZ + 1) > MAX_CHUNKS_IN_VIEW) {
			return;
		}

		if (!cacheValid || seed.getAsLong() != cachedSeed || firstX != cachedFirstX || lastX != cachedLastX
			|| firstZ != cachedFirstZ || lastZ != cachedLastZ) {
			rebuild(seed.getAsLong(), firstX, lastX, firstZ, lastZ);
		}

		for (int i = 0; i < cachedCount; i += 2) {
			int chunkX = cachedChunks[i];
			int chunkZ = cachedChunks[i + 1];
			int x1 = (int) Math.round(view.x(chunkX * 16.0));
			int y1 = (int) Math.round(view.y(chunkZ * 16.0));
			int x2 = (int) Math.round(view.x(chunkX * 16.0 + 16));
			int y2 = (int) Math.round(view.y(chunkZ * 16.0 + 16));
			context.fill(x1, y1, x2, y2, COLOR);
		}
	}

	private void rebuild(long seed, int firstX, int lastX, int firstZ, int lastZ) {
		int[] found = new int[64];
		int count = 0;
		for (int z = firstZ; z <= lastZ; z++) {
			for (int x = firstX; x <= lastX; x++) {
				if (SlimeChunks.isSlimeChunk(seed, x, z)) {
					if (count + 2 > found.length) {
						found = java.util.Arrays.copyOf(found, found.length * 2);
					}
					found[count++] = x;
					found[count++] = z;
				}
			}
		}
		cachedChunks = found;
		cachedCount = count;
		cachedSeed = seed;
		cachedFirstX = firstX;
		cachedLastX = lastX;
		cachedFirstZ = firstZ;
		cachedLastZ = lastZ;
		cacheValid = true;
	}
}
