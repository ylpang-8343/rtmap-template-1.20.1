package com.cabbage.rtmap.client.map;

import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

/**
 * Which picture of a dimension a stored map tile belongs to. A dimension can have several, for example the
 * Nether seen from below its roof and from above it, and each is saved and cached on its own.
 *
 * @param layer {@link #DEFAULT} is what the dimension looks like normally (the surface, or for a dimension with a
 *              roof the floor of its highest open space) and keeps the original tile directory, so maps saved
 *              before views existed stay valid. Any other layer is stored in a folder of its own.
 */
public record MapView(RegistryKey<World> dimension, String layer) {
	public static final String DEFAULT = "default";
	/** A dimension with a roof seen from above, including the roof itself and whatever is built on it. */
	public static final String ROOF = "roof";

	public static MapView defaultOf(RegistryKey<World> dimension) {
		return new MapView(dimension, DEFAULT);
	}

	public boolean isDefault() {
		return DEFAULT.equals(layer);
	}
}
