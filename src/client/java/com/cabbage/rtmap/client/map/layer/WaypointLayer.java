package com.cabbage.rtmap.client.map.layer;

import com.cabbage.rtmap.client.config.ClientConfig;
import com.cabbage.rtmap.client.waypoint.DimensionScale;
import com.cabbage.rtmap.client.waypoint.Waypoint;
import com.cabbage.rtmap.client.waypoint.WaypointStore;
import com.cabbage.rtmap.client.waypoint.Waypoints;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.RotationAxis;

/**
 * Waypoints as small coloured plates with their initials. The full-screen map also writes the name underneath.
 * On a rotated minimap the plates are turned back so the letters stay readable.
 */
public final class WaypointLayer implements MapLayer {
	private static final int ICON_HEIGHT = 11;
	private static final int MIN_ICON_WIDTH = 11;

	@Override
	public String id() {
		return "waypoints";
	}

	@Override
	public boolean defaultEnabled() {
		return true;
	}

	@Override
	public void render(DrawContext context, LayerView view) {
		if (!WaypointStore.isLoaded()) {
			return;
		}
		TextRenderer font = MinecraftClient.getInstance().textRenderer;
		String dimension = Waypoints.dimensionId(view.world());

		// Anything further out than the view (plus room for the icon) cannot be on screen.
		double limitX = view.halfWidthBlocks() * view.zoom() + 40;
		double limitY = view.halfHeightBlocks() * view.zoom() + 40;

		boolean includeOther = ClientConfig.showOtherDimensionWaypoints();
		for (Waypoint waypoint : WaypointStore.waypoints()) {
			if (!WaypointStore.isVisible(waypoint)) {
				continue;
			}
			double[] position = DimensionScale.positionOn(waypoint, dimension, includeOther);
			if (position == null) {
				continue;
			}
			double x = view.x(position[0]);
			double y = view.y(position[1]);
			if (Math.abs(x) > limitX || Math.abs(y) > limitY) {
				continue;
			}
			boolean fromOtherDimension = !waypoint.dimension.equals(dimension);
			drawIcon(context, font, waypoint, (int) Math.round(x), (int) Math.round(y), view.rotationDegrees(),
				!view.minimap(), fromOtherDimension);
		}
	}

	/** Draws one waypoint plate centred on ({@code x}, {@code y}); also used to find what was clicked. */
	public static void drawIcon(DrawContext context, TextRenderer font, Waypoint waypoint, int x, int y, float rotation,
		boolean label, boolean dimmed) {
		MatrixStack matrices = context.getMatrices();
		matrices.push();
		matrices.translate(x, y, 0);
		if (rotation != 0f) {
			matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(-rotation));
		}

		String initials = waypoint.initials();
		int width = Math.max(MIN_ICON_WIDTH, font.getWidth(initials) + 5);
		int left = -width / 2;
		int top = -ICON_HEIGHT / 2;
		// A waypoint from the other dimension is shown fainter, so it is clear it is only a converted position.
		int alpha = dimmed ? 0x80000000 : 0xFF000000;
		context.fill(left - 1, top - 1, left + width + 1, top + ICON_HEIGHT + 1, alpha);
		context.fill(left, top, left + width, top + ICON_HEIGHT, alpha | waypoint.color);
		context.drawText(font, Text.literal(initials), -font.getWidth(initials) / 2, top + 2,
			(textColor(waypoint.color) & 0xFFFFFF) | alpha, false);

		if (label) {
			int nameWidth = font.getWidth(waypoint.name);
			context.drawTextWithShadow(font, Text.literal(waypoint.name), -nameWidth / 2, top + ICON_HEIGHT + 3,
				dimmed ? 0xB0FFFFFF : 0xFFFFFF);
		}
		matrices.pop();
	}

	/** Black or white, whichever is easier to read on the given colour. */
	public static int textColor(int rgb) {
		int red = (rgb >> 16) & 0xFF;
		int green = (rgb >> 8) & 0xFF;
		int blue = rgb & 0xFF;
		return (red * 299 + green * 587 + blue * 114) / 1000 > 140 ? 0xFF000000 : 0xFFFFFFFF;
	}
}
