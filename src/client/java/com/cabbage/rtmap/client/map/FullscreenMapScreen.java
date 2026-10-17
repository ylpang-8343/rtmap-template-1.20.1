package com.cabbage.rtmap.client.map;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.cabbage.rtmap.chunk.ChunkSnapshot;
import com.cabbage.rtmap.chunk.ChunkTimingResult;
import com.cabbage.rtmap.client.chunk.ChunkDataClient;
import com.cabbage.rtmap.client.chunk.ChunkInfo;
import com.cabbage.rtmap.client.config.ClientConfig;
import com.cabbage.rtmap.client.map.layer.ChunkStateLayer;
import com.cabbage.rtmap.client.map.layer.LayerView;
import com.cabbage.rtmap.client.map.layer.MapLayer;
import com.cabbage.rtmap.client.map.layer.MapLayers;
import com.cabbage.rtmap.client.map.layer.PortalLayer;
import com.cabbage.rtmap.client.measure.MeasureTool;
import com.cabbage.rtmap.client.network.ClientSession;
import com.cabbage.rtmap.client.perf.ChunkTimingClient;
import com.cabbage.rtmap.client.perf.ServerPerformanceClient;
import com.cabbage.rtmap.client.perf.TimingPanel;
import com.cabbage.rtmap.client.portal.Portal;
import com.cabbage.rtmap.client.portal.PortalStore;
import com.cabbage.rtmap.client.ui.ContextMenu;
import com.cabbage.rtmap.client.ui.Icon;
import com.cabbage.rtmap.client.ui.IconButton;
import com.cabbage.rtmap.client.ui.Theme;
import com.cabbage.rtmap.client.waypoint.DimensionScale;
import com.cabbage.rtmap.client.waypoint.Waypoint;
import com.cabbage.rtmap.client.waypoint.WaypointEditorScreen;
import com.cabbage.rtmap.client.waypoint.WaypointGroup;
import com.cabbage.rtmap.client.waypoint.WaypointManagerScreen;
import com.cabbage.rtmap.client.waypoint.WaypointSharing;
import com.cabbage.rtmap.client.waypoint.WaypointStore;
import com.cabbage.rtmap.client.waypoint.Waypoints;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.Heightmap;

/**
 * Full-screen map. Drag to pan, scroll to zoom around the cursor, right-click for a menu, press the map key again
 * to close. A toolbar along the top holds the layer switches and tools, and a status line along the bottom shows
 * where the cursor is, in the way JourneyMap lays its screen out.
 */
public final class FullscreenMapScreen extends Screen {
	// Zoom is measured in real screen pixels per block, so "1.00x" is one pixel per block whatever the GUI scale
	// is. 0.01 is about where the coarsest overview level (MapCache.MAX_LEVEL) stops having enough detail.
	private static final double MIN_ZOOM = 0.01;
	private static final double MAX_ZOOM = 32.0;
	/** What one click of a zoom button multiplies the zoom by. */
	private static final double BUTTON_ZOOM_STEP = 1.5;

	private static final int BUTTON = 20;
	private static final int GAP = 2;
	private static final int BAR_PADDING = 3;
	private static final int SEPARATOR_GAP = 8;
	private static final int STATUS_HEIGHT = 14;
	private static final long FLASH_MS = 2500;

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

	// A press on the map is either a click (places a measuring point) or the start of a drag (pans). Which one is
	// only known on release, by how far the mouse moved.
	private boolean pressOnMap;
	private double dragDistance;

	private final ContextMenu menu = new ContextMenu();
	private final TimingPanel timingPanel = new TimingPanel();
	private final List<int[]> separators = new ArrayList<>();
	private int toolbarHeight = BUTTON + 2 * BAR_PADDING;
	private IconButton clearButton;
	/** A short message in the status area, such as "coordinates copied". */
	private Text flash = Text.empty();
	private long flashUntil;

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
		menu.close();

		// Left side: one switch per layer, then the measuring tool. A null stands for a gap between groups.
		List<ClickableWidget> left = new ArrayList<>();
		for (MapLayer layer : MapLayers.toggleable()) {
			if (layer.id().equals("measure")) {
				continue; // shown by the measuring tool's own buttons; the layer can still be switched in the settings
			}
			left.add(new IconButton(0, 0, BUTTON, () -> Icon.forLayer(layer.id()), () -> layerLabel(layer),
				() -> MapLayers.isEnabled(layer), pressed -> MapLayers.setEnabled(layer, !MapLayers.isEnabled(layer))));
		}
		left.add(null);
		left.add(new IconButton(0, 0, BUTTON, FullscreenMapScreen::measureIcon, FullscreenMapScreen::measureLabel,
			MeasureTool::active, pressed -> {
				MeasureTool.Mode[] modes = MeasureTool.Mode.values();
				MeasureTool.setMode(modes[(MeasureTool.mode().ordinal() + 1) % modes.length]);
			}));
		clearButton = IconButton.action(0, 0, BUTTON, Icon.CLEAR, Text.translatable("measure.rtmap.clear"),
			pressed -> MeasureTool.clear());
		left.add(clearButton);

		// Chunk timing is a run you start yourself, so its button only exists where the server lets you.
		if (ChunkTimingClient.available()) {
			left.add(null);
			left.add(new IconButton(0, 0, BUTTON, () -> Icon.RECORD, FullscreenMapScreen::timingLabel,
				ChunkTimingClient::running, pressed -> ChunkTimingClient.toggle(ClientConfig.timingSeconds())));
		}

		// Right side: zoom, back to the player, then the two screens.
		List<ClickableWidget> right = new ArrayList<>();
		right.add(IconButton.action(0, 0, BUTTON, Icon.ZOOM_OUT, Text.translatable("map.rtmap.zoom_out"),
			pressed -> zoom = MathHelper.clamp(zoom / BUTTON_ZOOM_STEP, MIN_ZOOM, MAX_ZOOM)));
		right.add(IconButton.action(0, 0, BUTTON, Icon.ZOOM_IN, Text.translatable("map.rtmap.zoom_in"),
			pressed -> zoom = MathHelper.clamp(zoom * BUTTON_ZOOM_STEP, MIN_ZOOM, MAX_ZOOM)));
		right.add(IconButton.action(0, 0, BUTTON, Icon.CENTER, Text.translatable("map.rtmap.center"), pressed -> {
			if (client.player != null) {
				cameraX = client.player.getX();
				cameraZ = client.player.getZ();
			}
		}));
		right.add(null);
		right.add(IconButton.action(0, 0, BUTTON, Icon.LIST, Text.translatable("screen.rtmap.waypoints"),
			pressed -> client.setScreen(new WaypointManagerScreen(this))));
		right.add(IconButton.action(0, 0, BUTTON, Icon.SETTINGS, Text.translatable("screen.rtmap.map.settings"),
			pressed -> client.setScreen(new MapSettingsScreen(this))));

		layoutToolbar(left, right);
	}

	/** Places the buttons: the right group hugs the right edge, the left group flows and wraps if the window is narrow. */
	private void layoutToolbar(List<ClickableWidget> left, List<ClickableWidget> right) {
		separators.clear();

		int rightWidth = groupWidth(right);
		int rightX = width - BAR_PADDING - rightWidth;
		placeRow(right, rightX, BAR_PADDING);

		int row = 0;
		int x = BAR_PADDING;
		int y = BAR_PADDING;
		for (ClickableWidget widget : left) {
			if (widget == null) {
				separators.add(new int[] {x + SEPARATOR_GAP / 2 - GAP / 2 - 1, y});
				x += SEPARATOR_GAP - GAP;
				continue;
			}
			int limit = row == 0 ? rightX - SEPARATOR_GAP : width - BAR_PADDING;
			if (x + BUTTON > limit && x > BAR_PADDING) {
				row++;
				x = BAR_PADDING;
				y += BUTTON + GAP;
			}
			widget.setX(x);
			widget.setY(y);
			addDrawableChild(widget);
			x += BUTTON + GAP;
		}
		toolbarHeight = (row + 1) * (BUTTON + GAP) + BAR_PADDING * 2 - GAP;
	}

	private static int groupWidth(List<ClickableWidget> group) {
		int total = 0;
		for (ClickableWidget widget : group) {
			total += widget == null ? SEPARATOR_GAP - GAP : BUTTON + GAP;
		}
		return total - GAP;
	}

	private void placeRow(List<ClickableWidget> group, int startX, int y) {
		int x = startX;
		for (ClickableWidget widget : group) {
			if (widget == null) {
				separators.add(new int[] {x + SEPARATOR_GAP / 2 - GAP / 2 - 1, y});
				x += SEPARATOR_GAP - GAP;
				continue;
			}
			widget.setX(x);
			widget.setY(y);
			addDrawableChild(widget);
			x += BUTTON + GAP;
		}
	}

	private static Text layerLabel(MapLayer layer) {
		MutableText name = Text.translatable("layer.rtmap." + layer.id());
		return name.append(": ").append(ScreenTexts.onOrOff(MapLayers.isEnabled(layer)));
	}

	private static Icon measureIcon() {
		return switch (MeasureTool.mode()) {
			case RECTANGLE -> Icon.RECTANGLE;
			case CIRCLE -> Icon.CIRCLE;
			default -> Icon.RULER;
		};
	}

	private static Text timingLabel() {
		return ChunkTimingClient.running()
			? Text.translatable("timing.rtmap.stop", ChunkTimingClient.secondsLeft())
			: Text.translatable("timing.rtmap.start", ClientConfig.timingSeconds());
	}

	private static Text measureLabel() {
		return Text.translatable("measure.rtmap.tool").append(": ")
			.append(Text.translatable("measure.rtmap.mode." + MeasureTool.mode().name().toLowerCase(Locale.ROOT)));
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

	/** Whether a screen position is on the toolbar or the status line rather than on the map. */
	private boolean overBar(double mouseX, double mouseY) {
		return mouseY < toolbarHeight || mouseY >= height - STATUS_HEIGHT;
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		context.fill(0, 0, width, height, Theme.MAP_BACKGROUND);
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
		drawToolbar(context);
		super.render(context, mouseX, mouseY, delta);
		drawStatusLine(context, player, mouseX, mouseY);
		drawTimingPanel(context, mouseX, mouseY);
		drawMeasurement(context);
		drawMessages(context, world);

		if (menu.isOpen()) {
			menu.render(context, textRenderer, mouseX, mouseY);
		} else if (!overBar(mouseX, mouseY) && !drawPortalTooltip(context, mouseX, mouseY)) {
			drawChunkTooltip(context, mouseX, mouseY);
		}
	}

	/** The summary and the list of expensive chunks from the last timing run, while its layer is on. */
	private void drawTimingPanel(DrawContext context, int mouseX, int mouseY) {
		ChunkTimingResult result = ChunkTimingClient.result();
		if (result == null || client.world == null || !ChunkTimingClient.available()
			|| !MapLayers.isEnabled(ChunkTimingClient.LAYER_ID)) {
			timingPanel.hide();
			return;
		}
		timingPanel.render(context, textRenderer, width, toolbarHeight + 4, mouseX, mouseY, result,
			Waypoints.dimensionId(client.world));
	}

	private void drawToolbar(DrawContext context) {
		Theme.bar(context, 0, 0, width, toolbarHeight, true);
		for (int[] separator : separators) {
			context.fill(separator[0], separator[1] + 2, separator[0] + 1, separator[1] + BUTTON - 2, Theme.BORDER);
		}
		if (clearButton != null) {
			clearButton.active = !MeasureTool.points().isEmpty();
		}
	}

	/** The line along the bottom: cursor position, chunk, the other dimension's coordinates, then you and the zoom. */
	private void drawStatusLine(DrawContext context, ClientPlayerEntity player, int mouseX, int mouseY) {
		int top = height - STATUS_HEIGHT;
		Theme.bar(context, 0, top, width, STATUS_HEIGHT, false);
		int textY = top + (STATUS_HEIGHT - 8) / 2 + 1;

		int blockX = MathHelper.floor(worldX(mouseX));
		int blockZ = MathHelper.floor(worldZ(mouseY));
		Text cursor = Text.translatable("map.rtmap.status.cursor", blockX, blockZ, blockX >> 4, blockZ >> 4);
		Text me = Text.translatable("map.rtmap.status.player", player.getBlockX(), player.getBlockY(), player.getBlockZ(),
			String.format(Locale.ROOT, "%.2f", zoom));

		int x = 6;
		context.drawText(textRenderer, cursor, x, textY, Theme.TEXT, false);
		x += textRenderer.getWidth(cursor) + 12;

		int meX = width - textRenderer.getWidth(me) - 6;

		// Room is tight on a small window, so the pieces are placed by priority: the cursor always, then the server's
		// tick time (in full if it fits, short if not), then "you", then the other dimension's coordinates.
		Text mspt = null;
		int msptX = 0;
		boolean showMe = true;
		Text msptLong = msptText(true);
		if (msptLong != null) {
			Text msptShort = msptText(false);
			if (meX - textRenderer.getWidth(msptLong) - 12 > x) {
				mspt = msptLong;
				msptX = meX - textRenderer.getWidth(msptLong) - 12;
			} else if (meX - textRenderer.getWidth(msptShort) - 12 > x) {
				mspt = msptShort;
				msptX = meX - textRenderer.getWidth(msptShort) - 12;
			} else if (width - textRenderer.getWidth(msptShort) - 6 > x) {
				// No room for "you" as well: the tick time wins.
				mspt = msptShort;
				msptX = width - textRenderer.getWidth(msptShort) - 6;
				showMe = false;
			}
		}
		int limit = mspt != null ? msptX : meX;

		// Where the same spot is in the other dimension of the overworld/nether pair, if there is room for it.
		String dimension = Waypoints.dimensionId(client.world);
		String other = DimensionScale.counterpart(dimension);
		if (other != null && ClientConfig.showConvertedCoordinates()) {
			Text converted = Text.translatable("screen.rtmap.map.converted", Waypoints.dimensionName(other),
				DimensionScale.convert(blockX, dimension, other), DimensionScale.convert(blockZ, dimension, other));
			if (x + textRenderer.getWidth(converted) + 12 < limit) {
				context.drawText(textRenderer, converted, x, textY, Theme.ACCENT, false);
			}
		}
		if (mspt != null) {
			context.drawText(textRenderer, mspt, msptX, textY, Theme.TEXT, false);
		}
		if (showMe && meX > x) {
			context.drawText(textRenderer, me, meX, textY, Theme.TEXT_MUTED, false);
		}
	}

	/**
	 * "MSPT 12.3 (worst 40.1)  TPS 20.0", or without the worst tick, coloured by how close the server is to its 50 ms
	 * budget. Null if the server does not share it or no reading has arrived yet.
	 */
	private Text msptText(boolean withWorst) {
		if (!ServerPerformanceClient.available()) {
			return null;
		}
		ServerPerformanceClient.want();
		float average = ServerPerformanceClient.averageMs();
		if (average < 0) {
			return null;
		}
		String averageText = String.format(Locale.ROOT, "%.1f", average);
		String tps = String.format(Locale.ROOT, "%.1f", Math.min(20.0, 1000.0 / Math.max(average, 1.0f)));
		int color = average < 40f ? 0x80FF80 : average < 50f ? 0xFFD27F : 0xFF6060;
		MutableText text = withWorst
			? Text.translatable("map.rtmap.status.mspt", averageText, String.format(Locale.ROOT, "%.1f", ServerPerformanceClient.worstMs()), tps)
			: Text.translatable("map.rtmap.status.mspt_short", averageText, tps);
		return text.styled(style -> style.withColor(color));
	}

	/** Things the player should know right now, stacked above the status line. */
	private void drawMessages(DrawContext context, ClientWorld world) {
		List<Text> messages = new ArrayList<>();
		int[] colors = new int[8];
		int count = 0;

		if (Util.getMeasuringTimeMs() < flashUntil) {
			colors[count++] = 0xFF80FF80;
			messages.add(flash);
		}
		if (world.getDimension().hasCeiling()) {
			colors[count++] = 0xFFA0A0A0;
			messages.add(Text.translatable("screen.rtmap.map.unsupported_dimension"));
		}
		// The slime chunk layer needs the world seed; without one it draws nothing, so say so.
		if (DimensionScale.OVERWORLD.equals(Waypoints.dimensionId(world)) && MapLayers.isEnabled("slime_chunks")
			&& ClientSession.seed().isEmpty()) {
			colors[count++] = 0xFFFF8080;
			messages.add(Text.translatable("screen.rtmap.map.no_seed"));
		}
		if (ChunkTimingClient.running()) {
			colors[count++] = 0xFFFFD27F;
			messages.add(Text.translatable("timing.rtmap.running", ChunkTimingClient.secondsLeft()));
		}
		if (ChunkTimingClient.problem() != 0) {
			colors[count++] = 0xFFFF8080;
			messages.add(Text.translatable("timing.rtmap.problem." + ChunkTimingClient.problem()));
		}
		// The chunk layers draw nothing when zoomed far out, or when the server said no; tell the player which.
		boolean chunkLayerOn = MapLayers.isEnabled("chunk_state") || MapLayers.isEnabled("chunk_load");
		if (chunkLayerOn && ChunkDataClient.denied()) {
			colors[count++] = 0xFFFF8080;
			messages.add(Text.translatable("screen.rtmap.map.chunk_denied"));
		} else if (chunkLayerOn && zoom < ChunkStateLayer.MIN_ZOOM) {
			colors[count++] = 0xFFFFD27F;
			messages.add(Text.translatable("screen.rtmap.map.chunk_zoom"));
		}

		int y = height - STATUS_HEIGHT - 14;
		for (int i = 0; i < messages.size(); i++) {
			Text message = messages.get(i);
			int textWidth = textRenderer.getWidth(message);
			int x = (width - textWidth) / 2;
			context.fill(x - 4, y - 2, x + textWidth + 4, y + 10, 0xA0000000);
			context.drawText(textRenderer, message, x, y, colors[i], false);
			y -= 14;
		}
	}

	/** Loading level, tickets and counts for the chunk under the cursor, when a chunk layer is on. */
	private void drawChunkTooltip(DrawContext context, int mouseX, int mouseY) {
		boolean dataOn = MapLayers.isEnabled("chunk_state") || MapLayers.isEnabled("chunk_load");
		ChunkTimingResult timing = MapLayers.isEnabled(ChunkTimingClient.LAYER_ID) ? ChunkTimingClient.result() : null;
		if ((!dataOn && timing == null) || timingPanel.contains(mouseX, mouseY)) {
			return;
		}
		for (Element child : children()) {
			if (child.isMouseOver(mouseX, mouseY)) {
				return;
			}
		}

		int chunkX = MathHelper.floor(worldX(mouseX)) >> 4;
		int chunkZ = MathHelper.floor(worldZ(mouseY)) >> 4;
		List<Text> lines = new ArrayList<>();
		ChunkSnapshot snapshot = dataOn ? ChunkDataClient.snapshot() : null;
		if (snapshot != null) {
			lines.addAll(ChunkInfo.lines(snapshot, chunkX, chunkZ));
		}
		if (timing != null && client.world != null) {
			List<Text> cost = ChunkInfo.timingLines(timing, Waypoints.dimensionId(client.world), chunkX, chunkZ);
			if (!cost.isEmpty()) {
				if (lines.isEmpty()) {
					lines.add(Text.translatable("chunk.rtmap.title", chunkX, chunkZ).formatted(Formatting.WHITE));
				}
				lines.addAll(cost);
			}
		}
		if (!lines.isEmpty()) {
			context.drawTooltip(textRenderer, lines, mouseX, mouseY);
		}
	}

	/** The numbers for the current measurement, in a box at the bottom left. */
	private void drawMeasurement(DrawContext context) {
		if (!MeasureTool.active()) {
			return;
		}
		List<Text> lines = MeasureTool.summary();
		if (lines.isEmpty()) {
			return;
		}
		int textWidth = 0;
		for (Text line : lines) {
			textWidth = Math.max(textWidth, textRenderer.getWidth(line));
		}
		int lineHeight = 11;
		int boxHeight = lines.size() * lineHeight + 6;
		int top = height - STATUS_HEIGHT - boxHeight - 6;
		Theme.panel(context, 4, top, textWidth + 10, boxHeight);
		for (int i = 0; i < lines.size(); i++) {
			context.drawTextWithShadow(textRenderer, lines.get(i), 9, top + 4 + i * lineHeight, 0xFFFFE066);
		}
	}

	/** If the cursor is on a portal icon, name its dimension and give its real coordinates. */
	private boolean drawPortalTooltip(DrawContext context, int mouseX, int mouseY) {
		if (client.world == null || !MapLayers.isEnabled("portals") || !PortalStore.isLoaded()) {
			return false;
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
			return false;
		}

		boolean nether = DimensionScale.NETHER.equals(closest.dimension());
		int color = PortalLayer.colorOf(closest.dimension());
		List<Text> lines = new ArrayList<>();
		lines.add(Text.translatable(nether ? "portal.rtmap.nether" : "portal.rtmap.overworld")
			.styled(style -> style.withColor(color)));
		lines.add(Text.literal(closest.x() + ", " + closest.y() + ", " + closest.z()));
		if (!closest.dimension().equals(dimension)) {
			lines.add(Text.translatable("portal.rtmap.converted").formatted(Formatting.GRAY));
		}
		context.drawTooltip(textRenderer, lines, mouseX, mouseY);
		return true;
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

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
		if (!pressOnMap) {
			return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
		}
		dragDistance += Math.abs(deltaX) + Math.abs(deltaY);
		cameraX -= deltaX / guiZoom();
		cameraZ -= deltaY / guiZoom();
		return true;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		menu.close();
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
		pressOnMap = false;
		if (menu.isOpen()) {
			return menu.click(mouseX, mouseY);
		}
		if (super.mouseClicked(mouseX, mouseY, button)) {
			return true; // a button took it
		}
		// A row of the timing panel centres the map on that chunk; the rest of the panel is not the map either.
		int[] listed = timingPanel.clickedChunk(mouseX, mouseY);
		if (listed != null) {
			cameraX = listed[0] * 16.0 + 8;
			cameraZ = listed[1] * 16.0 + 8;
			return true;
		}
		if (timingPanel.contains(mouseX, mouseY)) {
			return true;
		}
		if (overBar(mouseX, mouseY)) {
			return true; // the empty part of a bar is not the map
		}
		pressOnMap = true;
		dragDistance = 0;

		// While measuring, the map is for placing points: a right click takes the last one back, and a left click
		// is judged on release (it may turn out to be a drag). Waypoints are left alone so they cannot get in the way.
		if (MeasureTool.active()) {
			if (button == 1) {
				MeasureTool.removeLast();
				return true;
			}
			return false;
		}
		// A right click opens the menu, but only on release, once it is clear it was not the start of a drag.
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

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		boolean handled = super.mouseReleased(mouseX, mouseY, button);
		boolean wasPressOnMap = pressOnMap;
		pressOnMap = false;

		// A short press that did not move the map is a click: add a measuring point where it happened.
		if (button == 0 && wasPressOnMap && MeasureTool.active() && dragDistance < 4
			&& client.world != null && MapStorage.isResolved()) {
			MeasureTool.addPoint(Waypoints.dimensionId(client.world),
				MathHelper.floor(worldX(mouseX)), MathHelper.floor(worldZ(mouseY)));
			return true;
		}
		if (button == 1 && wasPressOnMap && !MeasureTool.active() && dragDistance < 4 && MapStorage.isResolved()) {
			openContextMenu((int) mouseX, (int) mouseY);
			return true;
		}
		return handled;
	}

	/** The right-click menu: for a waypoint if the click was on one, otherwise for the spot on the map. */
	private void openContextMenu(int mouseX, int mouseY) {
		if (client.world == null) {
			return;
		}
		Waypoint waypoint = Waypoints.ready() ? waypointAt(mouseX, mouseY) : null;
		List<ContextMenu.Entry> entries = new ArrayList<>();

		if (waypoint != null) {
			entries.add(ContextMenu.Entry.of(Text.translatable("menu.rtmap.edit_waypoint"), () ->
				client.setScreen(new WaypointEditorScreen(this, waypoint, waypoint.x, waypoint.y, waypoint.z, waypoint.dimension))));
			entries.add(ContextMenu.Entry.of(Text.translatable("menu.rtmap.teleport_waypoint"), () -> {
				Waypoints.teleport(waypoint);
				client.setScreen(null);
			}));
			// Opens the chat box with the text filled in; the player decides whether to send it.
			entries.add(ContextMenu.Entry.of(Text.translatable("menu.rtmap.share_waypoint"), () ->
				client.setScreen(new ChatScreen(WaypointSharing.format(waypoint)))));
			entries.add(ContextMenu.Entry.confirming(Text.translatable("menu.rtmap.delete_waypoint"),
				Text.translatable("menu.rtmap.confirm_delete"), () -> WaypointStore.remove(waypoint)));
		} else {
			int blockX = MathHelper.floor(worldX(mouseX));
			int blockZ = MathHelper.floor(worldZ(mouseY));
			int blockY = surfaceY(blockX, blockZ);
			if (Waypoints.ready()) {
				entries.add(ContextMenu.Entry.of(Text.translatable("menu.rtmap.new_waypoint"), () -> openNewWaypointAt(blockX, blockZ)));
			}
			entries.add(ContextMenu.Entry.of(Text.translatable("menu.rtmap.copy_coordinates"), () -> {
				client.keyboard.setClipboard(blockX + " " + blockY + " " + blockZ);
				flash = Text.translatable("menu.rtmap.copied", blockX + ", " + blockY + ", " + blockZ);
				flashUntil = Util.getMeasuringTimeMs() + FLASH_MS;
			}));
			entries.add(ContextMenu.Entry.of(Text.translatable("menu.rtmap.teleport_here"), () -> {
				Waypoints.teleport(Waypoint.create("", blockX, blockY, blockZ, Waypoints.dimensionId(client.world),
					WaypointGroup.DEFAULT_ID, 0xFFFFFF));
				client.setScreen(null);
			}));
		}
		menu.open(textRenderer, mouseX, mouseY, width, height, entries);
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

	/** Height: the real surface if that part of the world is loaded, otherwise wherever the player is. */
	private int surfaceY(int blockX, int blockZ) {
		int y = client.player == null ? 64 : client.player.getBlockY();
		if (client.world != null && client.world.getChunkManager().isChunkLoaded(blockX >> 4, blockZ >> 4)) {
			y = client.world.getTopY(Heightmap.Type.WORLD_SURFACE, blockX, blockZ);
		}
		return y;
	}

	private void openNewWaypointAt(int blockX, int blockZ) {
		if (client.world == null || client.player == null || !Waypoints.ready()) {
			return;
		}
		client.setScreen(new WaypointEditorScreen(this, null, blockX, surfaceY(blockX, blockZ), blockZ,
			Waypoints.dimensionId(client.world)));
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		// Escape closes an open menu first, and only then the map.
		if (keyCode == 256 && menu.isOpen()) {
			menu.close();
			return true;
		}
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
