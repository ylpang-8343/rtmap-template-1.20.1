package com.cabbage.rtmap.client.ui;

import java.util.List;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * The little menu that opens on a right click, as in Xaero's and JourneyMap's maps. The screen owns it: it asks
 * {@link #click} first, and draws it last so it sits on top of everything.
 */
public final class ContextMenu {
	private static final int ROW_HEIGHT = 14;
	private static final int PADDING = 8;

	/** One line. A confirming entry needs a second click, and shows {@code confirmLabel} after the first. */
	public static final class Entry {
		private final Text label;
		private final Text confirmLabel;
		private final Runnable action;
		private boolean armed;

		private Entry(Text label, Text confirmLabel, Runnable action) {
			this.label = label;
			this.confirmLabel = confirmLabel;
			this.action = action;
		}

		public static Entry of(Text label, Runnable action) {
			return new Entry(label, null, action);
		}

		public static Entry confirming(Text label, Text confirmLabel, Runnable action) {
			return new Entry(label, confirmLabel, action);
		}

		private Text shown() {
			return armed ? confirmLabel.copy().formatted(Formatting.RED) : label;
		}
	}

	private List<Entry> entries = List.of();
	private int x;
	private int y;
	private int width;
	private boolean open;

	public boolean isOpen() {
		return open;
	}

	public void close() {
		open = false;
		entries = List.of();
	}

	/** Opens the menu with its top-left corner near (mouseX, mouseY), kept inside the screen. */
	public void open(TextRenderer textRenderer, int mouseX, int mouseY, int screenWidth, int screenHeight, List<Entry> items) {
		entries = items;
		int widest = 0;
		for (Entry entry : items) {
			widest = Math.max(widest, textRenderer.getWidth(entry.label));
			if (entry.confirmLabel != null) {
				widest = Math.max(widest, textRenderer.getWidth(entry.confirmLabel));
			}
		}
		width = widest + PADDING * 2;
		int height = items.size() * ROW_HEIGHT + 4;
		x = Math.max(2, Math.min(mouseX, screenWidth - width - 2));
		y = Math.max(2, Math.min(mouseY, screenHeight - height - 2));
		open = true;
	}

	public void render(DrawContext context, TextRenderer textRenderer, int mouseX, int mouseY) {
		if (!open) {
			return;
		}
		Theme.panel(context, x, y, width, entries.size() * ROW_HEIGHT + 4);
		for (int i = 0; i < entries.size(); i++) {
			int rowY = y + 2 + i * ROW_HEIGHT;
			if (mouseX >= x + 1 && mouseX < x + width - 1 && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT) {
				context.fill(x + 1, rowY, x + width - 1, rowY + ROW_HEIGHT, Theme.BUTTON_HOVER);
			}
			context.drawText(textRenderer, entries.get(i).shown(), x + PADDING, rowY + 3, Theme.TEXT, false);
		}
	}

	/** Handles a click while the menu is open. Always consumed: a click elsewhere only closes the menu. */
	public boolean click(double mouseX, double mouseY) {
		if (!open) {
			return false;
		}
		int row = (int) Math.floor((mouseY - y - 2) / ROW_HEIGHT);
		boolean inside = mouseX >= x && mouseX < x + width && row >= 0 && row < entries.size();
		if (!inside) {
			close();
			return true;
		}
		Entry entry = entries.get(row);
		if (entry.confirmLabel != null && !entry.armed) {
			entry.armed = true;
			return true;
		}
		close();
		entry.action.run();
		return true;
	}
}
