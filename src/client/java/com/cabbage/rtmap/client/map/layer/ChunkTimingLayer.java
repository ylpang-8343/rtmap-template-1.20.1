package com.cabbage.rtmap.client.map.layer;

import com.cabbage.rtmap.chunk.ChunkTimingResult;
import com.cabbage.rtmap.client.map.MapRenderer;
import com.cabbage.rtmap.client.perf.ChunkTimingClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.MathHelper;

/**
 * Colours the chunks that used server time in the last timing run, from green to red by milliseconds per tick.
 * Only the most expensive chunks are listed in a result, so chunks that are not coloured used little or none.
 * Only drawn on the full-screen map.
 */
public final class ChunkTimingLayer implements MapLayer {
	/** The cost, in milliseconds of server time per tick, at which a chunk is drawn fully red. */
	private static final double RED_MS_PER_TICK = 2.0;
	/** The list is short, so this layer can stay on at much smaller scales than the ones that fill every chunk. */
	private static final double MIN_ZOOM = 0.05;

	@Override
	public String id() {
		return ChunkTimingClient.LAYER_ID;
	}

	@Override
	public boolean available() {
		return ChunkTimingClient.available();
	}

	@Override
	public void render(DrawContext context, LayerView view) {
		ChunkTimingResult result = ChunkTimingClient.result();
		if (result == null || view.minimap() || view.zoom() * MapRenderer.guiScale() < MIN_ZOOM) {
			return;
		}
		ChunkTimingResult.Dimension dimension = result.dimension(view.world().getRegistryKey().getValue().toString());
		if (dimension == null) {
			return;
		}

		double ticks = Math.max(1, result.ticks());
		for (int i = 0; i < dimension.size(); i++) {
			int chunkX = dimension.chunkX(i);
			int chunkZ = dimension.chunkZ(i);
			if (chunkX * 16 > view.rightBlock() || chunkX * 16 + 16 < view.leftBlock()
				|| chunkZ * 16 > view.bottomBlock() || chunkZ * 16 + 16 < view.topBlock()) {
				continue;
			}
			double millisPerTick = dimension.totalMicros(i) / 1000.0 / ticks;
			int x1 = (int) Math.round(view.x(chunkX * 16.0));
			int y1 = (int) Math.round(view.y(chunkZ * 16.0));
			int x2 = (int) Math.round(view.x(chunkX * 16.0 + 16));
			int y2 = (int) Math.round(view.y(chunkZ * 16.0 + 16));
			// At least one pixel, or a chunk would vanish when zoomed far out.
			context.fill(x1, y1, Math.max(x2, x1 + 1), Math.max(y2, y1 + 1), colorOf(millisPerTick));
		}
	}

	/** Green for little, through yellow, to red; more is also more opaque. */
	private static int colorOf(double millisPerTick) {
		float t = (float) MathHelper.clamp(Math.sqrt(millisPerTick / RED_MS_PER_TICK), 0.0, 1.0);
		int rgb = MathHelper.hsvToRgb((1.0f - t) / 3.0f, 0.9f, 1.0f);
		int alpha = 0x50 + (int) (0x90 * t);
		return (alpha << 24) | (rgb & 0xFFFFFF);
	}
}
