package com.cabbage.rtmap.client.map;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;

/** Draws the map tiles. Shared by the full-screen map and the minimap. */
public final class MapRenderer {
	private MapRenderer() {
	}

	/**
	 * Draws the map around ({@code centerX}, {@code centerZ}). The caller must already have moved the origin to the
	 * middle of the view (and rotated it, if wanted) and call {@link MapCache#beginFrame()} once per frame.
	 *
	 * @param zoom       screen pixels per block
	 * @param halfWidth  half the visible width in blocks (be generous if the view is rotated)
	 * @param halfHeight half the visible height in blocks
	 */
	public static void drawTiles(DrawContext context, ClientWorld world, double centerX, double centerZ, double zoom,
		double halfWidth, double halfHeight) {
		// Pick the coarsest overview level whose pixels are still at least one screen pixel wide, so the number
		// of tiles on screen stays small however far out we are.
		int level = Math.max(0, Math.min(MapCache.MAX_LEVEL, (int) Math.ceil(-Math.log(zoom) / Math.log(2))));
		int tileBlocks = MapRegion.SIZE << level;

		int firstX = Math.floorDiv((int) Math.floor(centerX - halfWidth), tileBlocks);
		int lastX = Math.floorDiv((int) Math.floor(centerX + halfWidth), tileBlocks);
		int firstZ = Math.floorDiv((int) Math.floor(centerZ - halfHeight), tileBlocks);
		int lastZ = Math.floorDiv((int) Math.floor(centerZ + halfHeight), tileBlocks);

		MatrixStack matrices = context.getMatrices();
		matrices.push();
		matrices.scale((float) zoom, (float) zoom, 1f);
		matrices.translate(-centerX, -centerZ, 0);
		RenderSystem.enableBlend();

		for (int tileZ = firstZ; tileZ <= lastZ; tileZ++) {
			for (int tileX = firstX; tileX <= lastX; tileX++) {
				MapRegion tile = MapCache.tileForDisplay(world.getRegistryKey(), level, tileX, tileZ);
				if (tile == null) {
					continue; // never explored, or still being read from disk
				}
				tile.uploadIfDirty();
				// Every tile texture is 512x512 pixels; coarser levels simply cover more blocks.
				context.drawTexture(tile.id(), tileX * tileBlocks, tileZ * tileBlocks, tileBlocks, tileBlocks,
					0f, 0f, MapRegion.SIZE, MapRegion.SIZE, MapRegion.SIZE, MapRegion.SIZE);
			}
		}

		matrices.pop();
	}
}
