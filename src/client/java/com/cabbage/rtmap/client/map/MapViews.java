package com.cabbage.rtmap.client.map;

import com.cabbage.rtmap.client.config.ClientConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;

/** Which {@link MapView} the player is looking at in the dimension they are in. Shared by the map and the minimap. */
public final class MapViews {
	private MapViews() {
	}

	/** The view shown for this world's dimension. Only a dimension with a roof has more than the default one. */
	public static MapView current(ClientWorld world) {
		if (world.getDimension().hasCeiling() && ClientConfig.roofView(world.getRegistryKey().getValue().toString())) {
			return new MapView(world.getRegistryKey(), MapView.ROOF);
		}
		return MapView.defaultOf(world.getRegistryKey());
	}

	/** Whether the roof (above-roof) view is on for the dimension the player is in. */
	public static boolean roofViewOn() {
		ClientWorld world = MinecraftClient.getInstance().world;
		return world != null && !current(world).isDefault();
	}

	public static void toggleRoof() {
		ClientWorld world = MinecraftClient.getInstance().world;
		if (world == null || !world.getDimension().hasCeiling()) {
			return;
		}
		String id = world.getRegistryKey().getValue().toString();
		ClientConfig.setRoofView(id, !ClientConfig.roofView(id));
	}
}
