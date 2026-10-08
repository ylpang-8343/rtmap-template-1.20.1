package com.cabbage.rtmap.client.map;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

/** Full-screen map: drag to pan, scroll to zoom around the cursor, press the map key again to close. */
public final class FullscreenMapScreen extends Screen {
	// 0.01 is about where the coarsest overview level (MapCache.MAX_LEVEL) stops having enough detail.
	private static final double MIN_ZOOM = 0.01;
	private static final double MAX_ZOOM = 16.0;

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

		drawRegions(context, world);
		drawPlayer(context, player);
		drawInfo(context, player, mouseX, mouseY);
		super.render(context, mouseX, mouseY, delta);
	}

	private void drawRegions(DrawContext context, ClientWorld world) {
		double left = worldX(0);
		double right = worldX(width);
		double top = worldZ(0);
		double bottom = worldZ(height);

		MatrixStack matrices = context.getMatrices();
		matrices.push();
		matrices.translate(width / 2.0, height / 2.0, 0);
		matrices.scale((float) zoom, (float) zoom, 1f);
		matrices.translate(-cameraX, -cameraZ, 0);
		RenderSystem.enableBlend();

		// Pick the coarsest overview level whose pixels are still at least one screen pixel wide, so the number
		// of tiles on screen stays small however far out we are.
		int level = MathHelper.clamp((int) Math.ceil(-Math.log(zoom) / Math.log(2)), 0, MapCache.MAX_LEVEL);
		int tileBlocks = MapRegion.SIZE << level;

		int firstX = Math.floorDiv((int) Math.floor(left), tileBlocks);
		int lastX = Math.floorDiv((int) Math.floor(right), tileBlocks);
		int firstZ = Math.floorDiv((int) Math.floor(top), tileBlocks);
		int lastZ = Math.floorDiv((int) Math.floor(bottom), tileBlocks);

		MapCache.beginFrame();
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

	private void drawPlayer(DrawContext context, ClientPlayerEntity player) {
		int x = (int) Math.round(width / 2.0 + (player.getX() - cameraX) * zoom);
		int y = (int) Math.round(height / 2.0 + (player.getZ() - cameraZ) * zoom);

		// A short line in the facing direction, then a dot on top.
		double yaw = Math.toRadians(player.getYaw());
		double dx = -Math.sin(yaw);
		double dz = Math.cos(yaw);
		for (int step = 2; step <= 10; step++) {
			int px = (int) Math.round(x + dx * step);
			int py = (int) Math.round(y + dz * step);
			context.fill(px - 1, py - 1, px + 1, py + 1, 0xFFFFFFFF);
		}
		context.fill(x - 3, y - 3, x + 3, y + 3, 0xFFFFFFFF);
		context.fill(x - 2, y - 2, x + 2, y + 2, 0xFFE03030);
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
