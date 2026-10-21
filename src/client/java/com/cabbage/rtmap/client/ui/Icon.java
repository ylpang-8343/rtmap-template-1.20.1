package com.cabbage.rtmap.client.ui;

import net.minecraft.client.gui.DrawContext;

/**
 * Small pictures drawn from rectangles, so the mod needs no texture files and the icons stay sharp at any GUI scale.
 * Each one is drawn inside a 16 x 16 box. When {@code on} is false the colours are washed out.
 */
public enum Icon {
	HEAT, STATE, SLIME, SPAWN, PORTAL, WAYPOINT, LIST, RULER, RECTANGLE, CIRCLE, CLEAR, SETTINGS, CENTER, ZOOM_IN, ZOOM_OUT,
	LAYER, TIMING, RECORD, ROOF, AUTO;

	public static final int SIZE = 16;

	/** The icon for a map layer id; layers without a picture of their own get a generic one. */
	public static Icon forLayer(String layerId) {
		return switch (layerId) {
			case "chunk_load" -> HEAT;
			case "chunk_state" -> STATE;
			case "slime_chunks" -> SLIME;
			case "spawn_ranges" -> SPAWN;
			case "portals" -> PORTAL;
			case "waypoints" -> WAYPOINT;
			case "measure" -> RULER;
			case "chunk_timing" -> TIMING;
			default -> LAYER;
		};
	}

	/** Draws the icon with its top-left corner at (x, y). */
	public void draw(DrawContext c, int x, int y, boolean on) {
		switch (this) {
			case HEAT -> {
				int[] colors = {0x40C040, 0x80D030, 0xE0D030, 0xA0D030, 0xE0A030, 0xE06030, 0xE0D030, 0xE06030, 0xE03030};
				for (int i = 0; i < 9; i++) {
					box(c, x + 1 + (i % 3) * 5, y + 1 + (i / 3) * 5, 4, 4, shade(colors[i], on));
				}
			}
			case STATE -> {
				for (int i = 0; i < 9; i++) {
					int cx = x + 1 + (i % 3) * 5;
					int cy = y + 1 + (i / 3) * 5;
					if (i == 4) {
						box(c, cx, cy, 4, 4, shade(0x40C040, on));
					} else {
						c.drawBorder(cx, cy, 4, 4, shade(i % 2 == 0 ? 0xE05030 : 0xE0C030, on));
					}
				}
			}
			case SLIME -> {
				box(c, x + 2, y + 3, 12, 11, shade(0x2F8F2F, on));
				box(c, x + 3, y + 4, 10, 9, shade(0x55FF55, on));
				box(c, x + 5, y + 6, 2, 2, shade(0x103010, on));
				box(c, x + 9, y + 6, 2, 2, shade(0x103010, on));
				box(c, x + 7, y + 10, 2, 1, shade(0x103010, on));
			}
			case SPAWN -> {
				circle(c, x + 8, y + 8, 7, shade(0xFF6060, on));
				circle(c, x + 8, y + 8, 3, shade(0xFFB060, on));
				box(c, x + 8, y + 8, 1, 1, shade(0xFFFFFF, on));
			}
			case PORTAL -> {
				box(c, x + 4, y + 1, 8, 14, shade(0x7A2DD0, on));
				box(c, x + 6, y + 3, 4, 10, shade(0x2A0F55, on));
				box(c, x + 7, y + 6, 2, 3, shade(0xC890FF, on));
			}
			case WAYPOINT -> {
				fillCircle(c, x + 8, y + 6, 5, shade(0xFFD27F, on));
				box(c, x + 5, y + 11, 7, 1, shade(0xFFD27F, on));
				box(c, x + 6, y + 12, 5, 1, shade(0xFFD27F, on));
				box(c, x + 7, y + 13, 3, 1, shade(0xFFD27F, on));
				box(c, x + 8, y + 14, 1, 1, shade(0xFFD27F, on));
				box(c, x + 7, y + 5, 3, 3, shade(0x303030, on));
			}
			case LIST -> {
				for (int row = 0; row < 3; row++) {
					box(c, x + 2, y + 3 + row * 4, 2, 2, shade(0xFFD27F, on));
					box(c, x + 6, y + 3 + row * 4, 8, 2, shade(0xD0D0D8, on));
				}
			}
			case RULER -> {
				box(c, x + 1, y + 5, 14, 6, shade(0xE8D890, on));
				for (int tick = 0; tick < 4; tick++) {
					box(c, x + 3 + tick * 3, y + 5, 1, tick % 2 == 0 ? 4 : 3, shade(0x403820, on));
				}
			}
			case RECTANGLE -> {
				c.drawBorder(x + 3, y + 3, 10, 10, shade(0xE8D890, on));
				box(c, x + 2, y + 2, 3, 3, shade(0xFFD27F, on));
				box(c, x + 11, y + 11, 3, 3, shade(0xFFD27F, on));
			}
			case CIRCLE -> {
				circle(c, x + 8, y + 8, 6, shade(0xE8D890, on));
				box(c, x + 7, y + 7, 2, 2, shade(0xFFD27F, on));
			}
			case CLEAR -> {
				for (int i = 0; i < 10; i++) {
					box(c, x + 3 + i, y + 3 + i, 2, 1, shade(0xFF6060, on));
					box(c, x + 12 - i, y + 3 + i, 2, 1, shade(0xFF6060, on));
				}
			}
			case SETTINGS -> {
				int[] knobs = {10, 4, 8};
				for (int row = 0; row < 3; row++) {
					box(c, x + 2, y + 3 + row * 4, 12, 1, shade(0xB0B0B8, on));
					box(c, x + knobs[row], y + 1 + row * 4, 3, 5, shade(0xFFD27F, on));
				}
			}
			case CENTER -> {
				circle(c, x + 8, y + 8, 5, shade(0xD0D0D8, on));
				box(c, x + 7, y + 7, 2, 2, shade(0xFFD27F, on));
				box(c, x + 7, y + 0, 2, 3, shade(0xD0D0D8, on));
				box(c, x + 7, y + 13, 2, 3, shade(0xD0D0D8, on));
				box(c, x + 0, y + 7, 3, 2, shade(0xD0D0D8, on));
				box(c, x + 13, y + 7, 3, 2, shade(0xD0D0D8, on));
			}
			case ZOOM_IN -> {
				box(c, x + 3, y + 7, 10, 2, shade(0xE0E0E8, on));
				box(c, x + 7, y + 3, 2, 10, shade(0xE0E0E8, on));
			}
			case ZOOM_OUT -> box(c, x + 3, y + 7, 10, 2, shade(0xE0E0E8, on));
			case AUTO -> {
				box(c, x + 7, y + 4, 2, 8, shade(0xD0D0D8, on));
				box(c, x + 7, y + 1, 2, 1, shade(0xFFD27F, on));
				box(c, x + 6, y + 2, 4, 1, shade(0xFFD27F, on));
				box(c, x + 5, y + 3, 6, 1, shade(0xFFD27F, on));
				box(c, x + 5, y + 12, 6, 1, shade(0xFFD27F, on));
				box(c, x + 6, y + 13, 4, 1, shade(0xFFD27F, on));
				box(c, x + 7, y + 14, 2, 1, shade(0xFFD27F, on));
			}
			case ROOF -> {
				box(c, x + 1, y + 2, 14, 3, shade(0xA0A0A8, on));
				box(c, x + 1, y + 5, 14, 1, shade(0x505058, on));
				box(c, x + 2, y + 7, 3, 7, shade(0xB03030, on));
				box(c, x + 7, y + 7, 3, 5, shade(0xB03030, on));
				box(c, x + 12, y + 7, 2, 8, shade(0xB03030, on));
			}
			case TIMING -> {
				circle(c, x + 8, y + 9, 6, shade(0xFFD27F, on));
				box(c, x + 6, y + 1, 4, 2, shade(0xFFD27F, on));
				box(c, x + 7, y + 5, 2, 5, shade(0xFFFFFF, on));
				box(c, x + 8, y + 9, 3, 1, shade(0xFF6060, on));
			}
			case RECORD -> {
				circle(c, x + 8, y + 8, 7, shade(0xB0B0B8, on));
				fillCircle(c, x + 8, y + 8, 4, shade(0xFF5050, on));
			}
			case LAYER -> {
				box(c, x + 2, y + 9, 12, 3, shade(0x8090B0, on));
				box(c, x + 2, y + 5, 12, 3, shade(0xA0B0D0, on));
				box(c, x + 2, y + 1, 12, 3, shade(0xC8D4F0, on));
			}
		}
	}

	/** A filled rectangle in an opaque colour. */
	private static void box(DrawContext c, int x, int y, int w, int h, int color) {
		c.fill(x, y, x + w, y + h, color);
	}

	/** A one pixel wide circle outline, drawn pixel by pixel (the midpoint algorithm). */
	private static void circle(DrawContext c, int cx, int cy, int r, int color) {
		int px = r;
		int py = 0;
		int err = 1 - r;
		while (px >= py) {
			plot8(c, cx, cy, px, py, color);
			py++;
			if (err < 0) {
				err += 2 * py + 1;
			} else {
				px--;
				err += 2 * (py - px) + 1;
			}
		}
	}

	private static void plot8(DrawContext c, int cx, int cy, int px, int py, int color) {
		int[][] offsets = {{px, py}, {py, px}, {-py, px}, {-px, py}, {-px, -py}, {-py, -px}, {py, -px}, {px, -py}};
		for (int[] offset : offsets) {
			box(c, cx + offset[0], cy + offset[1], 1, 1, color);
		}
	}

	private static void fillCircle(DrawContext c, int cx, int cy, int r, int color) {
		for (int dy = -r; dy <= r; dy++) {
			int half = (int) Math.round(Math.sqrt(r * r - dy * dy));
			box(c, cx - half, cy + dy, half * 2 + 1, 1, color);
		}
	}

	/** Opaque colour; blended towards grey when the icon is switched off. */
	private static int shade(int rgb, boolean on) {
		if (on) {
			return 0xFF000000 | rgb;
		}
		int r = (rgb >> 16) & 0xFF;
		int g = (rgb >> 8) & 0xFF;
		int b = rgb & 0xFF;
		int grey = (r + g + b) / 3;
		r = (r + grey * 2) / 3 * 5 / 8;
		g = (g + grey * 2) / 3 * 5 / 8;
		b = (b + grey * 2) / 3 * 5 / 8;
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}
}
