package com.cabbage.rtmap.client.map.layer;

import com.cabbage.rtmap.chunk.ChunkSnapshot;
import com.cabbage.rtmap.client.chunk.ChunkDataClient;
import com.cabbage.rtmap.client.map.MapRenderer;
import com.cabbage.rtmap.network.RTMapChannels;
import net.minecraft.client.gui.DrawContext;

/**
 * Shows which chunks are loaded and how: green where entities run, yellow where only blocks run, red for the
 * border ring. Chunks held by a force-load, a portal or the spawn area get an outline. Needs the server part (or
 * singleplayer) and is only drawn on the full-screen map.
 */
public final class ChunkStateLayer implements MapLayer {
	/** Below this zoom (real screen pixels per block) a chunk is under four pixels and the layer is not drawn. */
	public static final double MIN_ZOOM = 0.25;
	/** Outlines need room; below this many pixels per chunk only the fill is drawn. */
	private static final double OUTLINE_MIN_CHUNK_PIXELS = 8;

	private static final int ENTITY_TICKING = 0x5040C040;
	private static final int BLOCK_TICKING = 0x50E0C030;
	private static final int BORDER = 0x50E05030;

	private static final int OUTLINE_FORCED = 0xFFFF55FF;
	private static final int OUTLINE_PORTAL = 0xFFAA66FF;
	private static final int OUTLINE_START = 0xFFFFFFFF;
	private static final int OUTLINE_DRAGON = 0xFFFF5555;

	@Override
	public String id() {
		return "chunk_state";
	}

	@Override
	public boolean available() {
		return ChunkDataClient.stateAvailable();
	}

	@Override
	public void render(DrawContext context, LayerView view) {
		if (view.minimap() || view.zoom() * MapRenderer.guiScale() < MIN_ZOOM) {
			return;
		}
		ChunkDataClient.want(view, RTMapChannels.QUERY_STATE);
		ChunkSnapshot snapshot = ChunkDataClient.snapshot();
		if (snapshot == null || !snapshot.hasState()) {
			return;
		}

		boolean outlines = view.zoom() * 16 * MapRenderer.guiScale() >= OUTLINE_MIN_CHUNK_PIXELS;
		int firstX = Math.max(snapshot.minChunkX(), (int) Math.floor(view.leftBlock() / 16.0));
		int lastX = Math.min(snapshot.minChunkX() + snapshot.width() - 1, (int) Math.floor(view.rightBlock() / 16.0));
		int firstZ = Math.max(snapshot.minChunkZ(), (int) Math.floor(view.topBlock() / 16.0));
		int lastZ = Math.min(snapshot.minChunkZ() + snapshot.height() - 1, (int) Math.floor(view.bottomBlock() / 16.0));

		for (int chunkZ = firstZ; chunkZ <= lastZ; chunkZ++) {
			for (int chunkX = firstX; chunkX <= lastX; chunkX++) {
				int state = snapshot.state(chunkX, chunkZ);
				int fill = switch (state & ChunkSnapshot.LEVEL_MASK) {
					case ChunkSnapshot.LEVEL_ENTITY_TICKING -> ENTITY_TICKING;
					case ChunkSnapshot.LEVEL_BLOCK_TICKING -> BLOCK_TICKING;
					case ChunkSnapshot.LEVEL_BORDER -> BORDER;
					default -> 0;
				};
				if (fill == 0) {
					continue;
				}
				int x1 = (int) Math.round(view.x(chunkX * 16.0));
				int y1 = (int) Math.round(view.y(chunkZ * 16.0));
				int x2 = (int) Math.round(view.x(chunkX * 16.0 + 16));
				int y2 = (int) Math.round(view.y(chunkZ * 16.0 + 16));
				context.fill(x1, y1, x2, y2, fill);

				int outline = outlines ? outlineOf(state) : 0;
				if (outline != 0) {
					context.drawBorder(x1, y1, x2 - x1, y2 - y1, outline);
				}
			}
		}
	}

	/** The most interesting ticket wins: a force-load matters more than a player standing nearby. */
	private static int outlineOf(int state) {
		if ((state & ChunkSnapshot.TICKET_FORCED) != 0) {
			return OUTLINE_FORCED;
		}
		if ((state & ChunkSnapshot.TICKET_PORTAL) != 0) {
			return OUTLINE_PORTAL;
		}
		if ((state & ChunkSnapshot.TICKET_START) != 0) {
			return OUTLINE_START;
		}
		if ((state & ChunkSnapshot.TICKET_DRAGON) != 0) {
			return OUTLINE_DRAGON;
		}
		return 0;
	}
}
