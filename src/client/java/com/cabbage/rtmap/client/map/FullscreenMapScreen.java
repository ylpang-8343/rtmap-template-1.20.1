package com.cabbage.rtmap.client.map;

import java.util.ArrayList;
import java.util.List;

import com.cabbage.rtmap.client.config.ClientConfig;
import com.cabbage.rtmap.client.network.ClientSession;
import com.cabbage.rtmap.client.map.layer.LayerView;
import com.cabbage.rtmap.client.map.layer.MapLayer;
import com.cabbage.rtmap.client.map.layer.MapLayers;
import com.cabbage.rtmap.client.map.layer.PortalLayer;
import com.cabbage.rtmap.client.portal.Portal;
import com.cabbage.rtmap.client.portal.PortalStore;
import com.cabbage.rtmap.client.waypoint.DimensionScale;
import com.cabbage.rtmap.client.waypoint.Waypoint;
import com.cabbage.rtmap.client.waypoint.WaypointEditorScreen;
import com.cabbage.rtmap.client.waypoint.WaypointManagerScreen;
import com.cabbage.rtmap.client.waypoint.WaypointStore;
import com.cabbage.rtmap.client.waypoint.Waypoints;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.Heightmap;

/**
 * Full-screen map: drag to pan, scroll to zoom around the cursor, press the map key again to close.
 * Buttons on the left switch layers on and off; the button at the top right opens the map settings.
 */
public final class FullscreenMapScreen extends Screen {
	// Zoom is measured in real screen pixels per block, so "1.00x" is one pixel per block whatever the GUI scale
	// is. 0.01 is about where the coarsest overview level (MapCache.MAX_LEVEL) stops having enough detail.
	private static final double MIN_ZOOM = 0.01;
	private static final double MAX_ZOOM = 32.0;

	private static final int BUTTON_WIDTH = 120;
	private static final int BUTTON_HEIGHT = 20;
	private static final int BUTTON_GAP = 2;

	// Camera center in block coordinates, and real screen pixels per block.
	private double cameraX;
	private double cameraZ;
	private double zoom = 1.0;

	// For double-click detection and for creating a waypoint where the cursor is.
	private long lastClickTime;
	private double lastClickX;
	private double lastClickZ;
	private double mouseGuiX;
	private double mouseGuiY;

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

		int y = 44;
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
		addDrawableChild(ButtonWidget.builder(Text.translatable("screen.rtmap.waypoints"),
			pressed -> client.setScreen(new WaypointManagerScreen(this)))
			.dimensions(width - BUTTON_WIDTH - 6, 6 + BUTTON_HEIGHT + BUTTON_GAP, BUTTON_WIDTH, BUTTON_HEIGHT).build());
	}

	private static Text layerLabel(MapLayer layer) {
		MutableText name = Text.translatable("layer.rtmap." + layer.id());
		return name.append(": ").append(ScreenTexts.onOrOff(MapLayers.isEnabled(layer)));
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	/** GUI pixels per block; the widgets and layers work in GUI pixels. */
	private double guiZoom() {
		return zoom / MapRenderer.guiScale();
	}

	private double worldX(double screenX) {
		return cameraX + (screenX - width / 2.0) / guiZoom();
	}

	private double worldZ(double screenY) {
		return cameraZ + (screenY - height / 2.0) / guiZoom();
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		context.fill(0, 0, width, height, 0xFF101018);
		mouseGuiX = mouseX;
		mouseGuiY = mouseY;

		ClientWorld world = client.world;
		ClientPlayerEntity player = client.player;
		if (world == null || player == null) {
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
		drawPortalTooltip(context, mouseX, mouseY);
	}

	/** If the cursor is on a portal icon, name its dimension and give its real coordinates. */
	private void drawPortalTooltip(DrawContext context, int mouseX, int mouseY) {
		if (client.world == null || !MapLayers.isEnabled("portals") || !PortalStore.isLoaded()) {
			return;
		}
		String dimension = Waypoints.dimensionId(client.world);
		double guiZoom = guiZoom();
		Portal closest = null;
		double closestDistance = Double.MAX_VALUE;
		for (Portal portal : PortalStore.portals()) {
			double[] position = PortalLayer.positionOn(portal, dimension);
			if (position == null) {
				continue;
			}
			double dx = width / 2.0 + (position[0] - cameraX) * guiZoom - mouseX;
			double dy = height / 2.0 + (position[1] - cameraZ) * guiZoom - mouseY;
			double distance = dx * dx + dy * dy;
			if (Math.abs(dx) <= PortalLayer.HOVER_RADIUS && Math.abs(dy) <= PortalLayer.HOVER_RADIUS && distance < closestDistance) {
				closest = portal;
				closestDistance = distance;
			}
		}
		if (closest == null) {
			return;
		}

		boolean nether = DimensionScale.NETHER.equals(closest.dimension());
		int color = PortalLayer.colorOf(closest.dimension());
		List<Text> lines = new ArrayList<>();
		lines.add(Text.translatable(nether ? "portal.rtmap.nether" : "portal.rtmap.overworld")
			.styled(style -> style.withColor(color)));
		lines.add(Text.literal(closest.x() + ", " + closest.y() + ", " + closest.z()));
		if (!closest.dimension().equals(dimension)) {
			lines.add(Text.translatable("portal.rtmap.converted").formatted(net.minecraft.util.Formatting.GRAY));
		}
		context.drawTooltip(textRenderer, lines, mouseX, mouseY);
	}

	private void drawMap(DrawContext context, ClientWorld world) {
		double guiZoom = guiZoom();
		double halfWidth = width / 2.0 / guiZoom;
		double halfHeight = height / 2.0 / guiZoom;

		MatrixStack matrices = context.getMatrices();
		matrices.push();
		matrices.translate(width / 2.0, height / 2.0, 0);

		MapCache.beginFrame();
		// There is no terrain view of dimensions with a roof (the nether) yet, but waypoints and portals still show.
		if (!world.getDimension().hasCeiling()) {
			MapRenderer.drawTiles(context, world, cameraX, cameraZ, guiZoom, halfWidth, halfHeight);
		}
		MapLayers.renderAll(context, new LayerView(world, cameraX, cameraZ, guiZoom, halfWidth, halfHeight, false, 0f));

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

		// Where the same spot is in the other dimension of the overworld/nether pair.
		String dimension = Waypoints.dimensionId(client.world);
		String other = DimensionScale.counterpart(dimension);
		if (other != null && ClientConfig.showConvertedCoordinates()) {
			context.drawTextWithShadow(textRenderer,
				Text.translatable("screen.rtmap.map.converted", Waypoints.dimensionName(other),
					DimensionScale.convert(blockX, dimension, other), DimensionScale.convert(blockZ, dimension, other)),
				6, 30, 0xFFD27F);
		}
		if (client.world.getDimension().hasCeiling()) {
			context.drawCenteredTextWithShadow(textRenderer, Text.translatable("screen.rtmap.map.unsupported_dimension"),
				width / 2, height - 14, 0xA0A0A0);
		}
		// The slime chunk layer needs the world seed; without one it draws nothing, so say so.
		if (DimensionScale.OVERWORLD.equals(dimension) && MapLayers.isEnabled("slime_chunks") && ClientSession.seed().isEmpty()) {
			context.drawCenteredTextWithShadow(textRenderer, Text.translatable("screen.rtmap.map.no_seed"),
				width / 2, height - 28, 0xFF8080);
		}
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
		cameraX -= deltaX / guiZoom();
		cameraZ -= deltaY / guiZoom();
		return true;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		// Keep the block under the cursor where it is while zooming.
		double anchorX = worldX(mouseX);
		double anchorZ = worldZ(mouseY);
		zoom = MathHelper.clamp(zoom * Math.pow(1.25, amount), MIN_ZOOM, MAX_ZOOM);
		cameraX = anchorX - (mouseX - width / 2.0) / guiZoom();
		cameraZ = anchorZ - (mouseY - height / 2.0) / guiZoom();
		return true;
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (super.mouseClicked(mouseX, mouseY, button)) {
			return true; // a button took it
		}
		if (button != 0 || !Waypoints.ready()) {
			return false;
		}

		// Clicking a waypoint edits it.
		Waypoint hit = waypointAt(mouseX, mouseY);
		if (hit != null) {
			client.setScreen(new WaypointEditorScreen(this, hit, hit.x, hit.y, hit.z, hit.dimension));
			return true;
		}

		// Double-clicking empty map creates a waypoint there.
		long now = Util.getMeasuringTimeMs();
		double blockX = worldX(mouseX);
		double blockZ = worldZ(mouseY);
		boolean doubleClick = now - lastClickTime < 400
			&& Math.abs(blockX - lastClickX) * guiZoom() < 4 && Math.abs(blockZ - lastClickZ) * guiZoom() < 4;
		lastClickTime = doubleClick ? 0 : now;
		lastClickX = blockX;
		lastClickZ = blockZ;
		if (doubleClick) {
			openNewWaypointAt(MathHelper.floor(blockX), MathHelper.floor(blockZ));
			return true;
		}
		return false;
	}

	/** The visible waypoint under a screen position, or null. */
	private Waypoint waypointAt(double mouseX, double mouseY) {
		if (client.world == null) {
			return null;
		}
		String dimension = Waypoints.dimensionId(client.world);
		double guiZoom = guiZoom();
		Waypoint closest = null;
		double closestDistance = Double.MAX_VALUE;
		boolean includeOther = ClientConfig.showOtherDimensionWaypoints();
		for (Waypoint waypoint : WaypointStore.waypoints()) {
			if (!WaypointStore.isVisible(waypoint)) {
				continue;
			}
			double[] position = DimensionScale.positionOn(waypoint, dimension, includeOther);
			if (position == null) {
				continue;
			}
			double dx = width / 2.0 + (position[0] - cameraX) * guiZoom - mouseX;
			double dy = height / 2.0 + (position[1] - cameraZ) * guiZoom - mouseY;
			// The icon is about 11 pixels wide and tall.
			if (Math.abs(dx) <= 7 && Math.abs(dy) <= 7 && dx * dx + dy * dy < closestDistance) {
				closest = waypoint;
				closestDistance = dx * dx + dy * dy;
			}
		}
		return closest;
	}

	private void openNewWaypointAt(int blockX, int blockZ) {
		if (client.world == null || client.player == null || !Waypoints.ready()) {
			return;
		}
		// Height: the real surface if that part of the world is loaded, otherwise wherever the player is.
		int y = client.player.getBlockY();
		if (client.world.getChunkManager().isChunkLoaded(blockX >> 4, blockZ >> 4)) {
			y = client.world.getTopY(Heightmap.Type.WORLD_SURFACE, blockX, blockZ);
		}
		client.setScreen(new WaypointEditorScreen(this, null, blockX, y, blockZ, Waypoints.dimensionId(client.world)));
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (ClientMap.openKey().matchesKey(keyCode, scanCode)) {
			close();
			return true;
		}
		if (Waypoints.ready() && Waypoints.createKey().matchesKey(keyCode, scanCode)) {
			openNewWaypointAt(MathHelper.floor(worldX(mouseGuiX)), MathHelper.floor(worldZ(mouseGuiY)));
			return true;
		}
		if (Waypoints.ready() && Waypoints.managerKey().matchesKey(keyCode, scanCode)) {
			client.setScreen(new WaypointManagerScreen(this));
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}
}
