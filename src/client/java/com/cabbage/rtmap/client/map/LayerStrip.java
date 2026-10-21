package com.cabbage.rtmap.client.map;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.cabbage.rtmap.client.ui.Theme;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;

/**
 * A vertical strip at the left of the full-screen map for choosing which layer of the dimension to look at, the way
 * JourneyMap's layer slider does: the highest layer is at the top. It starts with the roof (only in a dimension with
 * one), then the default layer, then one cell for every 16 block slice of the dimension. Layers that have map data
 * are brighter, the one being shown is gold, and a white mark shows the layer the player is in. Click a cell or use the
 * mouse wheel over the strip to change layer.
 */
public final class LayerStrip {
	private static final int WIDTH = 24;
	private static final int PADDING = 2;
	private static final int MAX_CELL = 11;
	private static final int MIN_CELL = 4;
	/** Cells at least this tall have room for a number. */
	private static final int LABEL_CELL = 9;

	private record Cell(String layer, String label) {
	}

	private final List<Cell> cells = new ArrayList<>();
	private ClientWorld cellsFor;
	private int x;
	private int y;
	private int height;
	private int cellHeight = MAX_CELL;
	private boolean visible;
	private int hovered = -1;
	private String playerLayer = "";
	private Set<String> withData = Set.of();
	private String selected = MapView.DEFAULT;

	public boolean contains(double mouseX, double mouseY) {
		return visible && mouseX >= x && mouseX < x + WIDTH && mouseY >= y && mouseY < y + height;
	}

	private void rebuild(ClientWorld world) {
		cells.clear();
		if (world.getDimension().hasCeiling()) {
			cells.add(new Cell(MapView.ROOF, "R"));
		}
		cells.add(new Cell(MapView.DEFAULT, "S"));
		for (int section = MapViews.topSection(world); section >= MapViews.bottomSection(world); section--) {
			cells.add(new Cell(MapView.sliceLayer(section), Integer.toString(section * 16)));
		}
		cellsFor = world;
	}

	public void hide() {
		visible = false;
		hovered = -1;
	}

	/** Draws the strip with its top left corner at (left, top), at most down to {@code maxBottom}. */
	public void render(DrawContext context, TextRenderer textRenderer, ClientWorld world, ClientPlayerEntity player,
		int left, int top, int maxBottom, int mouseX, int mouseY) {
		if (cellsFor != world) {
			rebuild(world);
		}
		MapView current = MapViews.current(world);
		selected = current.layer();
		withData = MapViews.layersWithData(world);
		playerLayer = world.getDimension().hasCeiling() && player.getBlockY() > MapViews.roofTopY(world)
			? MapView.ROOF : MapView.sliceLayer(MapViews.playerSection(world, player));

		x = left;
		y = top;
		cellHeight = MathHelper.clamp((maxBottom - top - 2 * PADDING) / cells.size(), MIN_CELL, MAX_CELL);
		height = cellHeight * cells.size() + 2 * PADDING;
		visible = true;
		hovered = contains(mouseX, mouseY) ? cellIndexAt(mouseY) : -1;

		Theme.panel(context, x, y, WIDTH, height);
		for (int i = 0; i < cells.size(); i++) {
			Cell cell = cells.get(i);
			int cellTop = y + PADDING + i * cellHeight;
			boolean isSelected = cell.layer().equals(selected);
			boolean hasData = hasData(cell.layer());

			int fill = isSelected ? 0xFFFFD27F : colorOf(cell.layer(), hasData);
			context.fill(x + PADDING, cellTop, x + WIDTH - PADDING, cellTop + cellHeight - 1, fill);
			if (i == hovered) {
				context.drawBorder(x + PADDING, cellTop, WIDTH - 2 * PADDING, cellHeight - 1, 0xFFFFFFFF);
			}
			if (cellHeight >= LABEL_CELL) {
				int labelColor = isSelected ? 0xFF202020 : hasData ? 0xFFFFFFFF : 0xFF909098;
				context.drawText(textRenderer, cell.label(), x + (WIDTH - textRenderer.getWidth(cell.label())) / 2,
					cellTop + (cellHeight - 1 - 8) / 2 + 1, labelColor, false);
			}
			if (cell.layer().equals(playerLayer)) {
				// The player's own layer: a white tick on the strip's edge.
				context.fill(x, cellTop + 1, x + PADDING, cellTop + cellHeight - 2, 0xFFFFFFFF);
			}
		}
	}

	private boolean hasData(String layer) {
		return layer.equals(MapView.DEFAULT) || layer.equals(selected) || withData.contains(layer);
	}

	private static int colorOf(String layer, boolean hasData) {
		if (MapView.ROOF.equals(layer)) {
			return hasData ? 0xFF8A8A98 : 0xFF3A3A44;
		}
		if (MapView.DEFAULT.equals(layer)) {
			return hasData ? 0xFF4F9F58 : 0xFF2F4F34;
		}
		return hasData ? 0xFF4A74B0 : 0xFF2C3242;
	}

	private int cellIndexAt(double mouseY) {
		int index = (int) Math.floor((mouseY - y - PADDING) / cellHeight);
		return index >= 0 && index < cells.size() ? index : -1;
	}

	/** The text for the cell under the cursor, drawn after everything else so it is on top. */
	public void renderTooltip(DrawContext context, TextRenderer textRenderer, ClientWorld world, int mouseX, int mouseY) {
		if (!visible || hovered < 0 || hovered >= cells.size()) {
			return;
		}
		String layer = cells.get(hovered).layer();
		List<Text> lines = new ArrayList<>();
		lines.add(MapViews.label(new MapView(world.getRegistryKey(), layer), world).copy().formatted(Formatting.WHITE));
		if (layer.equals(selected)) {
			lines.add(Text.translatable("view.rtmap.shown").formatted(Formatting.GOLD));
		}
		if (layer.equals(playerLayer)) {
			lines.add(Text.translatable("view.rtmap.you_here").formatted(Formatting.GRAY));
		}
		lines.add(Text.translatable(hasData(layer) ? "view.rtmap.has_data" : "view.rtmap.no_data").formatted(Formatting.GRAY));
		context.drawTooltip(textRenderer, lines, mouseX, mouseY);
	}

	/** Selects the cell that was clicked. */
	public void click(ClientWorld world, double mouseX, double mouseY, int button) {
		int index = cellIndexAt(mouseY);
		if (button == 0 && index >= 0) {
			MapViews.choose(world, cells.get(index).layer());
		}
	}

	/** Moves one layer up (wheel up) or down. Returns true if the wheel was used up by the strip. */
	public boolean scroll(ClientWorld world, double amount) {
		int current = -1;
		for (int i = 0; i < cells.size(); i++) {
			if (cells.get(i).layer().equals(selected)) {
				current = i;
			}
		}
		if (current < 0) {
			return true;
		}
		int next = MathHelper.clamp(current + (amount > 0 ? -1 : 1), 0, cells.size() - 1);
		if (next != current) {
			MapViews.choose(world, cells.get(next).layer());
		}
		return true;
	}
}
