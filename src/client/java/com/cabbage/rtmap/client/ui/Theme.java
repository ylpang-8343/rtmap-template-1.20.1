package com.cabbage.rtmap.client.ui;

import net.minecraft.client.gui.DrawContext;

/**
 * The one look shared by every RTMap screen: a dark blue-grey backdrop (the same colour the empty map is drawn in),
 * framed panels, and a gold accent. Using these instead of vanilla's dirt or dim-world backgrounds is what keeps
 * the map, the settings and the waypoint screens from looking like different mods.
 */
public final class Theme {
	/** What an unexplored part of the map looks like; screens use it so they do not clash with the map. */
	public static final int MAP_BACKGROUND = 0xFF101018;
	public static final int PANEL = 0xE0181824;
	public static final int PANEL_BAR = 0xD0101018;
	public static final int BORDER = 0xFF343450;
	public static final int ACCENT = 0xFFFFD27F;
	public static final int TEXT = 0xFFFFFFFF;
	public static final int TEXT_MUTED = 0xFFA0A0A8;

	public static final int BUTTON_OFF = 0xC0202030;
	public static final int BUTTON_ON = 0xD02E4A78;
	public static final int BUTTON_HOVER = 0xD0404068;
	public static final int BUTTON_DISABLED = 0x80181820;

	private Theme() {
	}

	/** Covers the whole screen, so nothing of the game behind it shows through half-way. */
	public static void backdrop(DrawContext context, int width, int height) {
		context.fillGradient(0, 0, width, height, 0xF2141420, 0xF20C0C14);
	}

	/** A framed panel. */
	public static void panel(DrawContext context, int x, int y, int width, int height) {
		context.fill(x, y, x + width, y + height, PANEL);
		context.drawBorder(x, y, width, height, BORDER);
	}

	/** A bar along an edge of the screen (the map's toolbar and status line). {@code bottomEdge} is the side with the line. */
	public static void bar(DrawContext context, int x, int y, int width, int height, boolean lineAtBottom) {
		context.fill(x, y, x + width, y + height, PANEL_BAR);
		int lineY = lineAtBottom ? y + height - 1 : y;
		context.fill(x, lineY, x + width, lineY + 1, BORDER);
	}
}
