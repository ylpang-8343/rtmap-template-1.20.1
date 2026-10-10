package com.cabbage.rtmap.client.map.layer;

import com.cabbage.rtmap.client.portal.Portal;
import com.cabbage.rtmap.client.portal.PortalStore;
import com.cabbage.rtmap.client.waypoint.DimensionScale;
import com.cabbage.rtmap.client.waypoint.Waypoints;
import net.minecraft.client.gui.DrawContext;

/**
 * Nether portals, with the area each one links to.
 *
 * <p>A portal in one dimension is found from the other dimension when the two positions, after converting by 8,
 * are within 128 blocks of each other (16 in nether coordinates). So around every portal this draws a square of
 * that size, in the coordinates of the map being viewed. Where you plan to build a new portal, the squares show
 * which existing portal it would link to.
 *
 * <p>The colour tells which dimension a portal is really in: purple for the nether, orange for the overworld.
 * A portal of the other dimension is drawn at its converted position and a little fainter.
 */
public final class PortalLayer implements MapLayer {
	/** Half the side of the link area, in blocks of the overworld. In the nether it is an eighth of that. */
	private static final double OVERWORLD_LINK_RADIUS = 128;
	private static final int ICON_HALF = 4;
	/** RGB of the nether and overworld portals. */
	private static final int NETHER_RGB = 0xB050FF;
	private static final int OVERWORLD_RGB = 0xFF9A3C;
	/** How close, in pixels, the cursor has to be to an icon to count as pointing at it. */
	public static final int HOVER_RADIUS = 7;

	@Override
	public String id() {
		return "portals";
	}

	@Override
	public boolean defaultEnabled() {
		return true;
	}

	/** The colour (RGB) used for portals that really are in {@code dimension}. */
	public static int colorOf(String dimension) {
		return DimensionScale.NETHER.equals(dimension) ? NETHER_RGB : OVERWORLD_RGB;
	}

	/**
	 * Where a portal appears on the map of {@code currentDimension}, in block coordinates of its middle, or null
	 * if it does not appear there (it is in neither dimension of the overworld/nether pair).
	 */
	public static double[] positionOn(Portal portal, String currentDimension) {
		String other = DimensionScale.counterpart(currentDimension);
		if (other == null || (!portal.dimension().equals(currentDimension) && !portal.dimension().equals(other))) {
			return null;
		}
		double factor = DimensionScale.factor(portal.dimension(), currentDimension);
		return new double[] {(portal.x() + 0.5) * factor, (portal.z() + 0.5) * factor};
	}

	@Override
	public void render(DrawContext context, LayerView view) {
		if (!PortalStore.isLoaded()) {
			return;
		}
		String dimension = Waypoints.dimensionId(view.world());
		if (DimensionScale.counterpart(dimension) == null) {
			return; // portals only connect the overworld and the nether
		}
		double radius = DimensionScale.OVERWORLD.equals(dimension) ? OVERWORLD_LINK_RADIUS : OVERWORLD_LINK_RADIUS / 8;

		double limitX = view.halfWidthBlocks() * view.zoom();
		double limitY = view.halfHeightBlocks() * view.zoom();
		double radiusPixels = radius * view.zoom();

		for (Portal portal : PortalStore.portals()) {
			double[] position = positionOn(portal, dimension);
			if (position == null) {
				continue;
			}
			double x = view.x(position[0]);
			double y = view.y(position[1]);
			// Skip portals whose area is entirely off screen.
			if (Math.abs(x) > limitX + radiusPixels || Math.abs(y) > limitY + radiusPixels) {
				continue;
			}

			int rgb = colorOf(portal.dimension());
			boolean own = portal.dimension().equals(dimension);

			// The link area is only worth drawing if it is big enough to see.
			if (radiusPixels >= 3) {
				int x1 = (int) Math.round(x - radiusPixels);
				int y1 = (int) Math.round(y - radiusPixels);
				int x2 = (int) Math.round(x + radiusPixels);
				int y2 = (int) Math.round(y + radiusPixels);
				context.fill(x1, y1, x2, y2, 0x18000000 | rgb);
				context.drawBorder(x1, y1, x2 - x1, y2 - y1, 0xB0000000 | rgb);
			}

			int cx = (int) Math.round(x);
			int cy = (int) Math.round(y);
			context.fill(cx - ICON_HALF - 1, cy - ICON_HALF - 1, cx + ICON_HALF + 1, cy + ICON_HALF + 1, 0xFF000000);
			context.fill(cx - ICON_HALF, cy - ICON_HALF, cx + ICON_HALF, cy + ICON_HALF, (own ? 0xFF000000 : 0xC0000000) | rgb);
		}
	}
}
