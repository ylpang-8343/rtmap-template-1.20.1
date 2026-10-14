package com.cabbage.rtmap.client.map.layer;

import com.cabbage.rtmap.chunk.ChunkSnapshot;
import com.cabbage.rtmap.client.chunk.ChunkDataClient;
import com.cabbage.rtmap.client.chunk.ChunkLoadScore;
import com.cabbage.rtmap.client.map.MapRenderer;
import com.cabbage.rtmap.network.RTMapChannels;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.MathHelper;

/**
 * Colours each chunk from green to red by an estimated load (see {@link ChunkLoadScore}). Chunks with nothing in
 * them stay clear. Only drawn on the full-screen map.
 */
public final class ChunkLoadLayer implements MapLayer {
	/** The score at which a chunk is drawn fully red. */
	private static final double RED_SCORE = 80.0;

	@Override
	public String id() {
		return "chunk_load";
	}

	@Override
	public boolean available() {
		return ChunkDataClient.loadAvailable();
	}

	@Override
	public void render(DrawContext context, LayerView view) {
		if (view.minimap() || view.zoom() * MapRenderer.guiScale() < ChunkStateLayer.MIN_ZOOM) {
			return;
		}
		// The loading level decides how much each entity or hopper weighs, so both are asked for.
		ChunkDataClient.want(view, RTMapChannels.QUERY_STATE | RTMapChannels.QUERY_LOAD);
		ChunkSnapshot snapshot = ChunkDataClient.snapshot();
		if (snapshot == null || !snapshot.hasState() || !snapshot.hasLoad()) {
			return;
		}

		int firstX = Math.max(snapshot.minChunkX(), (int) Math.floor(view.leftBlock() / 16.0));
		int lastX = Math.min(snapshot.minChunkX() + snapshot.width() - 1, (int) Math.floor(view.rightBlock() / 16.0));
		int firstZ = Math.max(snapshot.minChunkZ(), (int) Math.floor(view.topBlock() / 16.0));
		int lastZ = Math.min(snapshot.minChunkZ() + snapshot.height() - 1, (int) Math.floor(view.bottomBlock() / 16.0));

		for (int chunkZ = firstZ; chunkZ <= lastZ; chunkZ++) {
			for (int chunkX = firstX; chunkX <= lastX; chunkX++) {
				double score = ChunkLoadScore.of(snapshot, chunkX, chunkZ);
				if (score <= 0.0) {
					continue;
				}
				int x1 = (int) Math.round(view.x(chunkX * 16.0));
				int y1 = (int) Math.round(view.y(chunkZ * 16.0));
				int x2 = (int) Math.round(view.x(chunkX * 16.0 + 16));
				int y2 = (int) Math.round(view.y(chunkZ * 16.0 + 16));
				context.fill(x1, y1, x2, y2, colorOf(score));
			}
		}
	}

	/** Green for a little, through yellow, to red; more load is also more opaque. */
	static int colorOf(double score) {
		// The square root spreads the low end out, where most of the interesting chunks are.
		float t = (float) MathHelper.clamp(Math.sqrt(score / RED_SCORE), 0.0, 1.0);
		int rgb = MathHelper.hsvToRgb((1.0f - t) / 3.0f, 0.9f, 1.0f);
		int alpha = 0x40 + (int) (0x80 * t);
		return (alpha << 24) | (rgb & 0xFFFFFF);
	}
}
