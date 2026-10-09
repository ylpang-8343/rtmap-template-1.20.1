package com.cabbage.rtmap.client.map.layer;

import java.util.List;

import com.cabbage.rtmap.client.config.ClientConfig;
import net.minecraft.client.gui.DrawContext;

/** All map layers, in drawing order (later ones are drawn on top). */
public final class MapLayers {
	private static final List<MapLayer> LAYERS = List.of(
		new SlimeChunkLayer(),
		new PlayerLayer()
	);

	private MapLayers() {
	}

	/** The layers the player can switch on and off, in drawing order. */
	public static List<MapLayer> toggleable() {
		return LAYERS.stream().filter(MapLayer::toggleable).toList();
	}

	public static boolean isEnabled(MapLayer layer) {
		return !layer.toggleable() || ClientConfig.isLayerEnabled(layer.id(), layer.defaultEnabled());
	}

	public static void setEnabled(MapLayer layer, boolean enabled) {
		ClientConfig.setLayerEnabled(layer.id(), enabled);
	}

	public static void renderAll(DrawContext context, LayerView view) {
		for (MapLayer layer : LAYERS) {
			if (isEnabled(layer)) {
				layer.render(context, view);
			}
		}
	}
}
