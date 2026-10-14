package com.cabbage.rtmap.client.map.layer;

import java.util.List;

import com.cabbage.rtmap.client.map.MapRenderer;
import com.cabbage.rtmap.client.measure.MeasureTool;
import com.cabbage.rtmap.client.waypoint.Waypoints;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;

/**
 * Draws the current measurement (see {@link MeasureTool}): the ruler's line and points, or a rectangle with the
 * chunk grid and region-file borders inside it, or a circle. It shows on the minimap as well as the big map.
 */
public final class MeasureLayer implements MapLayer {
	private static final int LINE = 0xFFFFE066;
	private static final int FILL = 0x30FFE066;
	private static final int REGION_BORDER = 0xFFFF9A3C;
	private static final int CHUNK_GRID = 0x30FFFFFF;
	/** The chunk grid is only drawn when a chunk is at least this many real screen pixels wide. */
	private static final double MIN_CHUNK_PIXELS = 8;

	@Override
	public String id() {
		return "measure";
	}

	@Override
	public boolean defaultEnabled() {
		return true;
	}

	@Override
	public void render(DrawContext context, LayerView view) {
		List<int[]> points = MeasureTool.points();
		if (!MeasureTool.active() || points.isEmpty()
			|| !Waypoints.dimensionId(view.world()).equals(MeasureTool.dimension())) {
			return;
		}

		switch (MeasureTool.mode()) {
			case RULER -> drawRuler(context, view, points);
			case RECTANGLE -> drawRectangle(context, view, points);
			case CIRCLE -> drawCircle(context, view, points);
			case OFF -> {
			}
		}
		for (int[] point : points) {
			drawPoint(context, view.x(point[0] + 0.5), view.y(point[1] + 0.5));
		}
	}

	private static void drawPoint(DrawContext context, double x, double y) {
		int cx = (int) Math.round(x);
		int cy = (int) Math.round(y);
		context.fill(cx - 3, cy - 3, cx + 4, cy + 4, 0xFF000000);
		context.fill(cx - 2, cy - 2, cx + 3, cy + 3, LINE);
	}

	private static void drawRuler(DrawContext context, LayerView view, List<int[]> points) {
		MinecraftClient client = MinecraftClient.getInstance();
		for (int i = 1; i < points.size(); i++) {
			int[] from = points.get(i - 1);
			int[] to = points.get(i);
			double x1 = view.x(from[0] + 0.5);
			double y1 = view.y(from[1] + 0.5);
			double x2 = view.x(to[0] + 0.5);
			double y2 = view.y(to[1] + 0.5);
			line(context, view, x1, y1, x2, y2, LINE);

			// The length of each segment, on the big map only; the minimap is too small for text.
			if (!view.minimap() && points.size() > 2) {
				String label = MeasureTool.number(MeasureTool.distance(from, to));
				int labelX = (int) Math.round((x1 + x2) / 2) - client.textRenderer.getWidth(label) / 2;
				int labelY = (int) Math.round((y1 + y2) / 2) - 11;
				context.drawTextWithShadow(client.textRenderer, Text.literal(label), labelX, labelY, 0xFFFFE066);
			}
		}
	}

	private static void drawRectangle(DrawContext context, LayerView view, List<int[]> points) {
		if (points.size() < 2) {
			return;
		}
		int minX = Math.min(points.get(0)[0], points.get(1)[0]);
		int maxX = Math.max(points.get(0)[0], points.get(1)[0]) + 1; // the far edge of the last block
		int minZ = Math.min(points.get(0)[1], points.get(1)[1]);
		int maxZ = Math.max(points.get(0)[1], points.get(1)[1]) + 1;

		double left = view.x(minX);
		double right = view.x(maxX);
		double top = view.y(minZ);
		double bottom = view.y(maxZ);

		// Fill, clipped to the view so a huge rectangle costs no more than the part that is on screen.
		double limitX = view.halfWidthBlocks() * view.zoom() + 2;
		double limitY = view.halfHeightBlocks() * view.zoom() + 2;
		int fx1 = (int) Math.round(Math.max(left, -limitX));
		int fy1 = (int) Math.round(Math.max(top, -limitY));
		int fx2 = (int) Math.round(Math.min(right, limitX));
		int fy2 = (int) Math.round(Math.min(bottom, limitY));
		if (fx2 > fx1 && fy2 > fy1) {
			context.fill(fx1, fy1, fx2, fy2, FILL);
		}

		// The chunk grid, if it is big enough on screen to be useful.
		double chunkPixels = 16 * view.zoom() * MapRenderer.guiScale();
		if (chunkPixels >= MIN_CHUNK_PIXELS) {
			gridLines(context, view, minX, maxX, minZ, maxZ, 16, CHUNK_GRID);
		}
		// Borders of the region files (512 blocks), in a stronger colour.
		gridLines(context, view, minX, maxX, minZ, maxZ, 512, REGION_BORDER);

		line(context, view, left, top, right, top, LINE);
		line(context, view, right, top, right, bottom, LINE);
		line(context, view, right, bottom, left, bottom, LINE);
		line(context, view, left, bottom, left, top, LINE);
	}

	/** Lines every {@code step} blocks inside the rectangle, for the part of it that is in view. */
	private static void gridLines(DrawContext context, LayerView view, int minX, int maxX, int minZ, int maxZ, int step, int color) {
		int viewLeft = (int) Math.floor(view.leftBlock());
		int viewRight = (int) Math.ceil(view.rightBlock());
		int viewTop = (int) Math.floor(view.topBlock());
		int viewBottom = (int) Math.ceil(view.bottomBlock());

		int fromZ = Math.max(minZ, viewTop);
		int toZ = Math.min(maxZ, viewBottom);
		// Lines on the rectangle's own edges are drawn by its outline, so only those strictly inside.
		for (int x = Math.floorDiv(Math.max(minX, viewLeft), step) * step + step; x < Math.min(maxX, viewRight); x += step) {
			if (x > minX && fromZ < toZ) {
				line(context, view, view.x(x), view.y(fromZ), view.x(x), view.y(toZ), color);
			}
		}
		int fromX = Math.max(minX, viewLeft);
		int toX = Math.min(maxX, viewRight);
		for (int z = Math.floorDiv(Math.max(minZ, viewTop), step) * step + step; z < Math.min(maxZ, viewBottom); z += step) {
			if (z > minZ && fromX < toX) {
				line(context, view, view.x(fromX), view.y(z), view.x(toX), view.y(z), color);
			}
		}
	}

	private static void drawCircle(DrawContext context, LayerView view, List<int[]> points) {
		if (points.size() < 2) {
			return;
		}
		int[] center = points.get(0);
		int[] edge = points.get(1);
		double radius = MeasureTool.distance(center, edge);
		double cx = view.x(center[0] + 0.5);
		double cy = view.y(center[1] + 0.5);
		double pixels = radius * view.zoom();
		if (pixels >= 2) {
			SpawnRangeLayer.drawRing(context, cx, cy, pixels, view.halfHeightBlocks() * view.zoom(), LINE & 0xFFFFFF, true);
		}
		// A line from the centre to the point that sets the radius.
		line(context, view, cx, cy, view.x(edge[0] + 0.5), view.y(edge[1] + 0.5), LINE);
	}

	/**
	 * A straight line between two points in view pixels, cut to the part that is on screen first so a very long
	 * line does not cost more than a short one. Drawn in real screen pixels, so it stays thin at any GUI scale.
	 */
	private static void line(DrawContext context, LayerView view, double x1, double y1, double x2, double y2, int color) {
		double limitX = view.halfWidthBlocks() * view.zoom() + 2;
		double limitY = view.halfHeightBlocks() * view.zoom() + 2;

		// Liang-Barsky clipping against the visible rectangle.
		double t0 = 0;
		double t1 = 1;
		double dx = x2 - x1;
		double dy = y2 - y1;
		double[] p = {-dx, dx, -dy, dy};
		double[] q = {x1 + limitX, limitX - x1, y1 + limitY, limitY - y1};
		for (int i = 0; i < 4; i++) {
			if (p[i] == 0) {
				if (q[i] < 0) {
					return; // parallel to this edge and outside it
				}
			} else {
				double r = q[i] / p[i];
				if (p[i] < 0) {
					if (r > t1) {
						return;
					}
					t0 = Math.max(t0, r);
				} else {
					if (r < t0) {
						return;
					}
					t1 = Math.min(t1, r);
				}
			}
		}
		double scale = MapRenderer.guiScale();
		double ax = (x1 + t0 * dx) * scale;
		double ay = (y1 + t0 * dy) * scale;
		double bx = (x1 + t1 * dx) * scale;
		double by = (y1 + t1 * dy) * scale;

		MatrixStack matrices = context.getMatrices();
		matrices.push();
		matrices.scale((float) (1.0 / scale), (float) (1.0 / scale), 1f);
		int steps = (int) Math.min(12_000, Math.ceil(Math.max(Math.abs(bx - ax), Math.abs(by - ay))));
		for (int i = 0; i <= steps; i++) {
			double t = steps == 0 ? 0 : (double) i / steps;
			int px = (int) Math.round(ax + (bx - ax) * t);
			int py = (int) Math.round(ay + (by - ay) * t);
			context.fill(px, py, px + 2, py + 2, color);
		}
		matrices.pop();
	}
}
