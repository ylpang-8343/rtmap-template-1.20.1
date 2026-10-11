package com.cabbage.rtmap.client.map.layer;

import com.cabbage.rtmap.client.config.ClientConfig;
import com.cabbage.rtmap.client.map.MapRenderer;
import com.cabbage.rtmap.client.waypoint.Waypoint;
import com.cabbage.rtmap.client.waypoint.WaypointStore;
import com.cabbage.rtmap.client.waypoint.Waypoints;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

/**
 * Mob spawning ranges around an AFK spot: a ring at 24 blocks (hostile mobs never spawn closer than this to a
 * player) and one at 128 blocks (they only spawn inside this sphere, and mobs further away despawn at once).
 *
 * <p>The rules are spheres; a map shows their projection on the ground, so at a different height from the centre
 * the real area is smaller. Rings are drawn around waypoints that have "spawn range" switched on, and optionally
 * around the player. They are only drawn in the waypoint's own dimension.
 */
public final class SpawnRangeLayer implements MapLayer {
	/** The ring colours (RGB) for the known radii; anything else is grey. */
	private static final int RED = 0xE5483D;
	private static final int ORANGE = 0xF4A03A;
	private static final int CYAN = 0x3AA0F4;
	private static final int GREY = 0xB0B0B0;

	@Override
	public String id() {
		return "spawn_ranges";
	}

	@Override
	public boolean defaultEnabled() {
		return true;
	}

	/** The ring colour for a radius in blocks. */
	public static int colorOf(int radius) {
		return switch (radius) {
			case 24 -> RED;
			case 32 -> ORANGE;
			case 128 -> CYAN;
			default -> GREY;
		};
	}

	@Override
	public void render(DrawContext context, LayerView view) {
		int[] radii = ClientConfig.spawnRangeRadii();
		if (radii.length == 0) {
			return;
		}
		String dimension = Waypoints.dimensionId(view.world());

		for (Waypoint waypoint : WaypointStore.waypoints()) {
			if (waypoint.spawnRange && waypoint.dimension.equals(dimension) && WaypointStore.isVisible(waypoint)) {
				drawRings(context, view, waypoint.x + 0.5, waypoint.z + 0.5, radii);
			}
		}

		if (ClientConfig.spawnRangeAroundPlayer()) {
			MinecraftClient client = MinecraftClient.getInstance();
			ClientPlayerEntity player = client.player;
			if (player != null) {
				Vec3d position = player.getLerpedPos(client.getTickDelta());
				drawRings(context, view, position.x, position.z, radii);
			}
		}
	}

	private static void drawRings(DrawContext context, LayerView view, double centerX, double centerZ, int[] radii) {
		double x = view.x(centerX);
		double y = view.y(centerZ);
		double limitX = view.halfWidthBlocks() * view.zoom();
		double limitY = view.halfHeightBlocks() * view.zoom();

		for (int radius : radii) {
			double pixels = radius * view.zoom();
			// Too small to see, or entirely outside the view.
			if (pixels < 2 || Math.abs(x) - pixels > limitX || Math.abs(y) - pixels > limitY) {
				continue;
			}
			drawRing(context, x, y, pixels, limitY, colorOf(radius), radius == smallest(radii));
			if (!view.minimap() && y - pixels > -limitY - 12 && y - pixels < limitY) {
				MinecraftClient client = MinecraftClient.getInstance();
				String label = Integer.toString(radius);
				context.drawTextWithShadow(client.textRenderer, Text.literal(label),
					(int) Math.round(x) - client.textRenderer.getWidth(label) / 2, (int) Math.round(y - pixels) - 11, 0xFF000000 | colorOf(radius));
			}
		}
	}

	private static int smallest(int[] radii) {
		int smallest = Integer.MAX_VALUE;
		for (int radius : radii) {
			smallest = Math.min(smallest, radius);
		}
		return smallest;
	}

	/**
	 * Draws one circle a row at a time, in real screen pixels so that the line is thin whatever the GUI scale is.
	 * The innermost ring also gets a faint fill: that is the area where nothing spawns.
	 */
	private static void drawRing(DrawContext context, double centerX, double centerY, double radius, double limitY, int rgb, boolean fill) {
		double scale = MapRenderer.guiScale();
		MatrixStack matrices = context.getMatrices();
		matrices.push();
		matrices.scale((float) (1.0 / scale), (float) (1.0 / scale), 1f);

		double cx = centerX * scale;
		double cy = centerY * scale;
		double r = radius * scale;
		double inner = r - 1.5; // a line about a pixel and a half wide
		int border = 0xD0000000 | rgb;
		int tint = 0x28000000 | rgb;

		// Only the rows that can be on screen.
		int top = (int) Math.floor(Math.max(cy - r, -limitY * scale - 2));
		int bottom = (int) Math.ceil(Math.min(cy + r, limitY * scale + 2));
		for (int row = top; row <= bottom; row++) {
			double dy = row + 0.5 - cy;
			double outer2 = r * r - dy * dy;
			if (outer2 < 0) {
				continue;
			}
			double dxOuter = Math.sqrt(outer2);
			int left = (int) Math.round(cx - dxOuter);
			int right = (int) Math.round(cx + dxOuter);
			double inner2 = inner * inner - dy * dy;
			if (inner2 <= 0) {
				context.fill(left, row, right + 1, row + 1, border);
				continue;
			}
			double dxInner = Math.sqrt(inner2);
			int innerLeft = (int) Math.round(cx - dxInner);
			int innerRight = (int) Math.round(cx + dxInner);
			context.fill(left, row, innerLeft + 1, row + 1, border);
			context.fill(innerRight, row, right + 1, row + 1, border);
			if (fill) {
				context.fill(innerLeft + 1, row, innerRight, row + 1, tint);
			}
		}
		matrices.pop();
	}
}
