package com.cabbage.rtmap.client.waypoint;

import com.cabbage.rtmap.client.config.ClientConfig;
import com.cabbage.rtmap.client.map.layer.WaypointLayer;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BeaconBlockEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

/**
 * Draws waypoints inside the world: a beam like a beacon's, and a floating plate with the initials and, when you
 * look towards it, the name and distance. Both are visible through walls. A waypoint further away than the game
 * can draw is shown at the edge of the drawable range in its direction, so it is never lost.
 */
public final class WorldWaypointRenderer {
	/** Labels are hidden unless the waypoint is within this angle of the crosshair (cosine of 12 degrees). */
	private static final double LABEL_COS = Math.cos(Math.toRadians(12));
	private static final float BEAM_INNER_RADIUS = 0.2f;
	private static final float BEAM_OUTER_RADIUS = 0.25f;
	private static final int BEAM_HEIGHT = 1024;
	private static final int FULL_BRIGHT = 0xF000F0;
	/** The plate is drawn this far above the waypoint's block so it does not sit in the ground. */
	private static final double PLATE_HEIGHT = 1.8;

	private WorldWaypointRenderer() {
	}

	public static void init() {
		WorldRenderEvents.LAST.register(WorldWaypointRenderer::render);
	}

	private static void render(WorldRenderContext context) {
		MinecraftClient client = MinecraftClient.getInstance();
		VertexConsumerProvider consumers = context.consumers();
		if (!ClientConfig.worldWaypoints() || !WaypointStore.isLoaded() || consumers == null || client.options.hudHidden) {
			return;
		}

		Camera camera = context.camera();
		Vec3d cameraPos = camera.getPos();
		MatrixStack matrices = context.matrixStack();
		TextRenderer font = client.textRenderer;
		String dimension = Waypoints.dimensionId(context.world());

		// The game cannot draw past its far plane, so stay a little inside it.
		double drawLimit = client.gameRenderer.getFarPlaneDistance() * 0.9;
		double maxDistance = ClientConfig.worldMaxDistance();
		Vec3d look = Vec3d.fromPolar(camera.getPitch(), camera.getYaw());

		boolean drewAnything = false;
		for (Waypoint waypoint : WaypointStore.waypoints()) {
			if (!waypoint.dimension.equals(dimension) || !WaypointStore.isVisible(waypoint)) {
				continue;
			}

			// Relative to the camera, because that is how the world is drawn.
			double dx = waypoint.x + 0.5 - cameraPos.x;
			double dy = waypoint.y + PLATE_HEIGHT - cameraPos.y;
			double dz = waypoint.z + 0.5 - cameraPos.z;
			double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
			if (distance < 2 || (maxDistance > 0 && distance > maxDistance)) {
				continue;
			}

			if (ClientConfig.worldBeams()) {
				double horizontal = Math.sqrt(dx * dx + dz * dz);
				if (horizontal >= ClientConfig.beamMinDistance() && distance < drawLimit) {
					drawBeam(matrices, consumers, context, waypoint, cameraPos, distance);
					drewAnything = true;
				}
			}

			boolean facing = (dx * look.x + dy * look.y + dz * look.z) / distance > LABEL_COS;
			boolean label = !ClientConfig.autoHideLabels() || facing;
			drawPlate(matrices, camera, font, consumers, waypoint, new Vec3d(dx, dy, dz), distance, drawLimit, label);
			drewAnything = true;
		}

		// The text and the beam go into shared buffers, which are normally drawn later; draw them now.
		if (drewAnything && consumers instanceof VertexConsumerProvider.Immediate immediate) {
			immediate.draw();
		}
	}

	private static void drawBeam(MatrixStack matrices, VertexConsumerProvider consumers, WorldRenderContext context,
		Waypoint waypoint, Vec3d cameraPos, double distance) {
		// Far away a beam of normal width would be thinner than a pixel, so widen it with distance.
		float widen = (float) Math.max(1.0, distance / 48.0);
		float[] color = {
			((waypoint.color >> 16) & 0xFF) / 255f,
			((waypoint.color >> 8) & 0xFF) / 255f,
			(waypoint.color & 0xFF) / 255f,
		};

		matrices.push();
		matrices.translate(waypoint.x - cameraPos.x, waypoint.y - cameraPos.y, waypoint.z - cameraPos.z);
		BeaconBlockEntityRenderer.renderBeam(matrices, consumers, BeaconBlockEntityRenderer.BEAM_TEXTURE,
			context.tickDelta(), 1.0f, context.world().getTime(), 0, BEAM_HEIGHT, color,
			BEAM_INNER_RADIUS * widen, BEAM_OUTER_RADIUS * widen);
		matrices.pop();
	}

	private static void drawPlate(MatrixStack matrices, Camera camera, TextRenderer font, VertexConsumerProvider consumers,
		Waypoint waypoint, Vec3d relative, double distance, double drawLimit, boolean label) {
		Vec3d position = relative;
		double drawDistance = distance;
		if (distance > drawLimit) {
			position = relative.multiply(drawLimit / distance);
			drawDistance = drawLimit;
		}

		matrices.push();
		matrices.translate(position.x, position.y, position.z);
		// Face the camera, like a name tag.
		matrices.multiply(camera.getRotation());
		// Keep the plate about the same size on screen: it grows with distance beyond a few blocks.
		float scale = (float) (0.025 * Math.max(1.0, drawDistance / 8.0));
		matrices.scale(-scale, -scale, scale);
		Matrix4f matrix = matrices.peek().getPositionMatrix();

		String initials = " " + waypoint.initials() + " ";
		int iconWidth = font.getWidth(initials);
		font.draw(Text.literal(initials), -iconWidth / 2f, 0, WaypointLayer.textColor(waypoint.color), false, matrix,
			consumers, TextRenderer.TextLayerType.SEE_THROUGH, 0xFF000000 | waypoint.color, FULL_BRIGHT);

		if (label) {
			String text = waypoint.name;
			if (ClientConfig.showDistance()) {
				text += "  " + Math.round(distance) + "m";
			}
			int textWidth = font.getWidth(text);
			font.draw(Text.literal(text), -textWidth / 2f, 11, 0xFFFFFFFF, false, matrix,
				consumers, TextRenderer.TextLayerType.SEE_THROUGH, 0x80000000, FULL_BRIGHT);
		}
		matrices.pop();
	}
}
