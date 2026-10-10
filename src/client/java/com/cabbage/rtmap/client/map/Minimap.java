package com.cabbage.rtmap.client.map;

import com.cabbage.rtmap.client.config.ClientConfig;
import com.cabbage.rtmap.client.config.ClientConfig.Corner;
import com.cabbage.rtmap.client.map.layer.LayerView;
import com.cabbage.rtmap.client.map.layer.MapLayers;
import com.cabbage.rtmap.client.waypoint.DimensionScale;
import com.cabbage.rtmap.client.waypoint.Waypoints;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.text.Text;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

/**
 * The minimap in a corner of the screen. By default it is square, in the top right, and north is up; size,
 * corner, zoom and rotation are the player's choice (see {@link ClientConfig}).
 */
public final class Minimap {
	private static final int MARGIN = 6;
	private static final int BORDER_COLOR = 0xFF000000;
	private static final int BACKGROUND_COLOR = 0xFF101018;
	/** Height of one row of status effect icons, which the vanilla HUD draws in the top right corner. */
	private static final int EFFECT_ROW_HEIGHT = 26;
	private static final int TEXT_HEIGHT = 11;

	private static KeyBinding zoomInKey;
	private static KeyBinding zoomOutKey;
	private static KeyBinding toggleKey;

	private Minimap() {
	}

	public static void init() {
		// Not Z/X: vanilla already binds X to "Load Hotbar Activator", and when two bindings share a key the
		// game only hands the press to one of them.
		zoomInKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.rtmap.minimap_zoom_in", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_EQUAL, "key.categories.rtmap"));
		zoomOutKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.rtmap.minimap_zoom_out", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_MINUS, "key.categories.rtmap"));
		toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.rtmap.toggle_minimap", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "key.categories.rtmap"));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (zoomInKey.wasPressed()) {
				ClientConfig.stepMinimapZoom(1);
			}
			while (zoomOutKey.wasPressed()) {
				ClientConfig.stepMinimapZoom(-1);
			}
			while (toggleKey.wasPressed()) {
				ClientConfig.setMinimapEnabled(!ClientConfig.minimapEnabled());
			}
		});

		HudRenderCallback.EVENT.register(Minimap::render);
	}

	private static void render(DrawContext context, float tickDelta) {
		MinecraftClient client = MinecraftClient.getInstance();
		ClientWorld world = client.world;
		ClientPlayerEntity player = client.player;
		if (!ClientConfig.minimapEnabled() || world == null || player == null
			|| client.options.hudHidden || client.options.debugEnabled
			|| client.currentScreen instanceof FullscreenMapScreen) {
			return;
		}

		int size = ClientConfig.minimapSize();
		Corner corner = ClientConfig.minimapCorner();
		boolean coordinates = ClientConfig.showCoordinates();

		int x = corner.isLeft() ? MARGIN : context.getScaledWindowWidth() - size - MARGIN;
		String dimension = Waypoints.dimensionId(world);
		String other = DimensionScale.counterpart(dimension);
		boolean converted = coordinates && other != null && ClientConfig.showConvertedCoordinates();
		int textSpace = coordinates ? (converted ? 2 : 1) * TEXT_HEIGHT + 2 : 0;
		int y;
		if (corner.isTop()) {
			y = MARGIN + (corner == Corner.TOP_RIGHT ? statusEffectHeight(player) : 0);
		} else {
			y = context.getScaledWindowHeight() - size - MARGIN - textSpace;
		}

		// Frame and background; everything else is clipped to the inside of the frame.
		context.fill(x - 1, y - 1, x + size + 1, y + size + 1, BORDER_COLOR);
		context.fill(x, y, x + size, y + size, BACKGROUND_COLOR);

		if (MapStorage.isResolved()) {
			drawMap(context, client, world, player, tickDelta, x, y, size);
		}

		if (coordinates) {
			String text = player.getBlockX() + ", " + player.getBlockY() + ", " + player.getBlockZ();
			int textWidth = client.textRenderer.getWidth(text);
			int textX = x + (size - textWidth) / 2;
			// Below the map normally; the bottom corners reserved room for it, the top corners put it underneath.
			int textY = y + size + 3;
			context.drawTextWithShadow(client.textRenderer, Text.literal(text), textX, textY, 0xFFFFFF);

			if (converted) {
				Text otherText = Text.translatable("minimap.rtmap.converted", Waypoints.dimensionName(other),
					DimensionScale.convert(player.getBlockX(), dimension, other),
					DimensionScale.convert(player.getBlockZ(), dimension, other));
				int otherX = x + (size - client.textRenderer.getWidth(otherText)) / 2;
				context.drawTextWithShadow(client.textRenderer, otherText, otherX, textY + TEXT_HEIGHT, 0xFFD27F);
			}
		}
	}

	private static void drawMap(DrawContext context, MinecraftClient client, ClientWorld world, ClientPlayerEntity player,
		float tickDelta, int x, int y, int size) {
		Vec3d position = player.getLerpedPos(tickDelta);
		double zoom = ClientConfig.minimapZoom();
		boolean rotate = ClientConfig.minimapRotate();

		double half = size / 2.0;
		// A rotated square needs to be covered out to its corners.
		double extent = rotate ? half * Math.sqrt(2) : half;
		double halfBlocks = extent / zoom;

		context.enableScissor(x, y, x + size, y + size);
		MatrixStack matrices = context.getMatrices();
		matrices.push();
		matrices.translate(x + half, y + half, 0);
		float rotation = 0f;
		if (rotate) {
			// Turn the map so that the way the player faces points up.
			rotation = 180f - player.getYaw(tickDelta);
			matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(rotation));
		}

		MapCache.beginFrame();
		// There is no terrain view of dimensions with a roof (the nether) yet, but waypoints and portals still show.
		if (!world.getDimension().hasCeiling()) {
			MapRenderer.drawTiles(context, world, position.x, position.z, zoom, halfBlocks, halfBlocks);
		}
		MapLayers.renderAll(context, new LayerView(world, position.x, position.z, zoom, halfBlocks, halfBlocks, true, rotation));

		matrices.pop();
		context.disableScissor();
	}

	/** Room taken by vanilla's status effect icons in the top right corner, so the minimap sits below them. */
	private static int statusEffectHeight(ClientPlayerEntity player) {
		boolean beneficial = false;
		boolean harmful = false;
		for (StatusEffectInstance effect : player.getStatusEffects()) {
			if (effect.getEffectType().isBeneficial()) {
				beneficial = true;
			} else {
				harmful = true;
			}
		}
		return (beneficial ? EFFECT_ROW_HEIGHT : 0) + (harmful ? EFFECT_ROW_HEIGHT : 0);
	}
}
