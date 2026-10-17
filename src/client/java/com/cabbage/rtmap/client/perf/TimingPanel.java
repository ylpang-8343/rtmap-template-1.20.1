package com.cabbage.rtmap.client.perf;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.cabbage.rtmap.chunk.ChunkTimingResult;
import com.cabbage.rtmap.client.ui.Theme;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/**
 * The box at the top right of the full-screen map that sums up the last timing run and lists its most expensive
 * chunks. Clicking a row centres the map on that chunk.
 */
public final class TimingPanel {
	private static final int ROWS = 8;
	private static final int LINE = 11;
	private static final int PADDING = 6;
	private static final int MAX_WIDTH = 270;

	private int x;
	private int y;
	private int width;
	private int height;
	private boolean visible;
	/** For each listed row: chunk x, chunk z and the row's top edge on screen. Filled in while drawing. */
	private final int[][] rows = new int[ROWS][3];
	private int rowCount;

	public boolean contains(double mouseX, double mouseY) {
		return visible && mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
	}

	/** The chunk of the row under the cursor, as {x, z}, or null. */
	public int[] clickedChunk(double mouseX, double mouseY) {
		if (!contains(mouseX, mouseY)) {
			return null;
		}
		for (int i = 0; i < rowCount; i++) {
			if (mouseY >= rows[i][2] && mouseY < rows[i][2] + LINE) {
				return new int[] {rows[i][0], rows[i][1]};
			}
		}
		return null;
	}

	/** Called when the panel is not drawn this frame, so it no longer takes clicks. */
	public void hide() {
		visible = false;
	}

	public void render(DrawContext context, TextRenderer textRenderer, int screenWidth, int top, int mouseX, int mouseY,
		ChunkTimingResult result, String dimensionId) {
		ChunkTimingResult.Dimension dimension = result.dimension(dimensionId);
		double ticks = Math.max(1, result.ticks());

		List<String> head = new ArrayList<>();
		head.add(Text.translatable("timing.rtmap.summary", result.ticks(), format(result.tickMicros() / 1000.0 / ticks)).getString());
		if (result.tickMicros() > 0) {
			head.add(Text.translatable("timing.rtmap.attributed", format(result.measuredMicros() / 1000.0 / ticks),
				String.format(Locale.ROOT, "%.0f", 100.0 * result.measuredMicros() / result.tickMicros())).getString());
		}
		if (dimension != null) {
			String kinds = kinds(dimension, ticks);
			if (!kinds.isEmpty()) {
				head.add(Text.translatable("timing.rtmap.kinds", kinds).getString());
			}
		}

		List<String> rowTexts = new ArrayList<>();
		rowCount = 0;
		if (dimension != null) {
			for (int i = 0; i < Math.min(ROWS, dimension.size()); i++) {
				rowTexts.add(Text.translatable("timing.rtmap.row", i + 1, dimension.chunkX(i), dimension.chunkZ(i),
					format(dimension.totalMicros(i) / 1000.0 / ticks), dominant(dimension, i)).getString());
			}
		}
		boolean realRows = !rowTexts.isEmpty();
		if (!realRows) {
			rowTexts.add(Text.translatable("timing.rtmap.none_here").getString());
		}

		String title = Text.translatable("timing.rtmap.panel_title").getString();
		int textWidth = textRenderer.getWidth(title);
		for (String line : head) {
			textWidth = Math.max(textWidth, textRenderer.getWidth(line));
		}
		for (String line : rowTexts) {
			textWidth = Math.max(textWidth, textRenderer.getWidth(line));
		}
		width = Math.min(Math.min(MAX_WIDTH, screenWidth / 2), textWidth + 2 * PADDING);
		height = PADDING * 2 + LINE * (1 + head.size()) + 4 + LINE * rowTexts.size();
		x = screenWidth - width - 4;
		y = top;
		visible = true;

		Theme.panel(context, x, y, width, height);
		int lineY = y + PADDING;
		int inner = width - 2 * PADDING;
		context.drawText(textRenderer, textRenderer.trimToWidth(title, inner), x + PADDING, lineY, Theme.ACCENT, false);
		lineY += LINE;
		for (String line : head) {
			context.drawText(textRenderer, textRenderer.trimToWidth(line, inner), x + PADDING, lineY, Theme.TEXT_MUTED, false);
			lineY += LINE;
		}
		lineY += 4;

		for (int i = 0; i < rowTexts.size(); i++) {
			boolean hovered = realRows && mouseX >= x && mouseX < x + width && mouseY >= lineY && mouseY < lineY + LINE;
			if (hovered) {
				context.fill(x + 1, lineY, x + width - 1, lineY + LINE, Theme.BUTTON_HOVER);
			}
			context.drawText(textRenderer, textRenderer.trimToWidth(rowTexts.get(i), inner), x + PADDING, lineY + 1, Theme.TEXT, false);
			if (realRows) {
				rows[rowCount][0] = dimension.chunkX(i);
				rows[rowCount][1] = dimension.chunkZ(i);
				rows[rowCount][2] = lineY;
				rowCount++;
			}
			lineY += LINE;
		}
	}

	/** The biggest costs of the dimension by kind, "Entities 5.1 ms, Block entities 2.0 ms". */
	private static String kinds(ChunkTimingResult.Dimension dimension, double ticks) {
		List<Integer> order = new ArrayList<>();
		for (int category = 0; category < ChunkTimingResult.CATEGORIES; category++) {
			if (dimension.categoryTotal(category) > 0) {
				order.add(category);
			}
		}
		order.sort((a, b) -> Long.compare(dimension.categoryTotal(b), dimension.categoryTotal(a)));
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < Math.min(3, order.size()); i++) {
			if (i > 0) {
				text.append(", ");
			}
			int category = order.get(i);
			text.append(categoryName(category)).append(' ')
				.append(format(dimension.categoryTotal(category) / 1000.0 / ticks)).append(" ms");
		}
		return text.toString();
	}

	private static String dominant(ChunkTimingResult.Dimension dimension, int entry) {
		int best = 0;
		for (int category = 1; category < ChunkTimingResult.CATEGORIES; category++) {
			if (dimension.micros(entry, category) > dimension.micros(entry, best)) {
				best = category;
			}
		}
		return categoryName(best);
	}

	public static String categoryName(int category) {
		return Text.translatable("timing.rtmap.category." + ChunkTimingResult.CATEGORY_KEYS[category]).getString();
	}

	public static String format(double millis) {
		return String.format(Locale.ROOT, millis < 10 ? "%.2f" : "%.1f", millis);
	}
}
