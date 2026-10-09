package com.cabbage.rtmap.client.map;

import com.cabbage.rtmap.client.map.layer.LayerView;
import com.cabbage.rtmap.client.map.layer.MapLayer;
import com.cabbage.rtmap.client.map.layer.MapLayers;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

/**
 * Full-screen map: drag to pan, scroll to zoom around the cursor, press the map key again to close.
 * Buttons on the left switch layers on and off; the button at the top right opens the map settings.
 */
public final class FullscreenMapScreen extends Screen {
	// 0.01 is about where the coarsest overview level (MapCache.MAX_LEVEL) stops having enough detail.
	private static final double MIN_ZOOM = 0.01;
	private static final double MAX_ZOOM = 16.0;

	private static final int BUTTON_WIDTH = 120;
	private static final int BUTTON_HEIGHT = 20;
	private static final int BUTTON_GAP = 2;

	// Camera center in block coordinates, and screen pixels per block.
	private double cameraX;
	private double cameraZ;
	private double zoom = 1.0;

	public FullscreenMapScreen() {
		super(Text.translatable("screen.rtmap.map"));
	}

	@Override
	protected void init() {
		ClientPlayerEntity player = client.player;
		if (player != null) {
			cameraX = player.getX();
			cameraZ = player.getZ();
		}

		int y = 34;
		for (MapLayer layer : MapLayers.toggleable()) {
			ButtonWidget button = ButtonWidget.builder(layerLabel(layer), pressed -> {
				MapLayers.setEnabled(layer, !MapLayers.isEnabled(layer));
				pressed.setMessage(layerLabel(layer));
			}).dimensions(6, y, BUTTON_WIDTH, BUTTON_HEIGHT).build();
			addDrawableChild(button);
			y += BUTTON_HEIGHT + BUTTON_GAP;
		}

		addDrawableChild(ButtonWidget.builder(Text.translatable("screen.rtmap.map.settings"),
			pressed -> client.setScreen(new MapSettingsScreen(this)))
			.dimensions(width - BUTTON_WIDTH - 6, 6, BUTTON_WIDTH, BUTTON_HEIGHT).build());
	}

	private static Text layerLabel(MapLayer layer) {
		MutableText name = Text.translatable("layer.rtmap." + layer.id());
		return name.append(": ").append(ScreenTexts.onOrOff(MapLayers.isEnabled(layer)));
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	private double worldX(double screenX) {
		return cameraX + (screenX - width / 2.0) / zoom;
	}

	private double worldZ(double screenY) {
		return cameraZ + (screenY - height / 2.0) / zoom;
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		context.fill(0, 0, width, height, 0xFF101018);

		ClientWorld world = client.world;
		ClientPlayerEntity player = client.player;
		if (world == null || player == null) {
			return;
		}

		if (world.getDimension().hasCeiling()) {
			context.drawCenteredTextWithShadow(textRenderer, Text.translatable("screen.rtmap.map.unsupported_dimension"),
				width / 2, height / 2, 0xFFFFFF);
			super.render(context, mouseX, mouseY, delta);
			return;
		}

		if (!MapStorage.isResolved()) {
			context.drawCenteredTextWithShadow(textRenderer, Text.translatable("screen.rtmap.map.loading"),
				width / 2, height / 2, 0xFFFFFF);
			super.render(context, mouseX, mouseY, delta);
			return;
		}

		drawMap(context, world);
		drawInfo(context, player, mouseX, mouseY);
		super.render(context, mouseX, mouseY, delta);
	}

	private void drawMap(DrawContext context, ClientWorld world) {
		double halfWidth = width / 2.0 / zoom;
		double halfHeight = height / 2.0 / zoom;

		MatrixStack matrices = context.getMatrices();
		matrices.push();
		matrices.translate(width / 2.0, height / 2.0, 0);

		MapCache.beginFrame();
		MapRenderer.drawTiles(context, world, cameraX, cameraZ, zoom, halfWidth, halfHeight);
		MapLayers.renderAll(context, new LayerView(world, cameraX, cameraZ, zoom, halfWidth, halfHeight, false));

		matrices.pop();
	}

	private void drawInfo(DrawContext context, ClientPlayerEntity player, int mouseX, int mouseY) {
		int blockX = MathHelper.floor(worldX(mouseX));
		int blockZ = MathHelper.floor(worldZ(mouseY));
		context.drawTextWithShadow(textRenderer,
			Text.literal("X " + blockX + "  Z " + blockZ + "   chunk " + (blockX >> 4) + ", " + (blockZ >> 4)),
			6, 6, 0xFFFFFF);
		context.drawTextWithShadow(textRenderer,
			Text.literal("you: " + player.getBlockX() + ", " + player.getBlockY() + ", " + player.getBlockZ()
				+ "   zoom " + String.format("%.2f", zoom) + "x"),
			6, 18, 0xAAAAAA);
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
		cameraX -= deltaX / zoom;
		cameraZ -= deltaY / zoom;
		return true;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		// Keep the block under the cursor where it is while zooming.
		double anchorX = worldX(mouseX);
		double anchorZ = worldZ(mouseY);
		zoom = MathHelper.clamp(zoom * Math.pow(1.25, amount), MIN_ZOOM, MAX_ZOOM);
		cameraX = anchorX - (mouseX - width / 2.0) / zoom;
		cameraZ = anchorZ - (mouseY - height / 2.0) / zoom;
		return true;
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (ClientMap.openKey().matchesKey(keyCode, scanCode)) {
			close();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}
}
