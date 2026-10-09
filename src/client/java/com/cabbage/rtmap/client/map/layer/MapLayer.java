package com.cabbage.rtmap.client.map.layer;

import net.minecraft.client.gui.DrawContext;

/** Something drawn on top of the map tiles, on both the full-screen map and the minimap. */
public interface MapLayer {
	/** Stable id used in the config file; the display name is the translation key {@code layer.rtmap.<id>}. */
	String id();

	/** Whether the player can switch this layer off. Layers that are not toggleable are always drawn. */
	default boolean toggleable() {
		return true;
	}

	default boolean defaultEnabled() {
		return false;
	}

	void render(DrawContext context, LayerView view);
}
