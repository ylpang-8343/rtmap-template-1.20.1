package com.cabbage.rtmap.client.map;

import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

/**
 * Which picture of a dimension a stored map tile belongs to. A dimension can have several, and each is saved and
 * cached on its own.
 *
 * @param layer one of:
 *              <ul>
 *              <li>{@link #DEFAULT}: what the dimension looks like normally (the surface, or for a dimension with a
 *              roof the floor of its highest open space). It keeps the original tile directory, so maps saved
 *              before views existed stay valid.</li>
 *              <li>{@link #ROOF}: a dimension with a roof seen from above, roof and whatever is built on it included.</li>
 *              <li>a slice, {@code "y<n>"}: what you see looking down from the top of the 16 block thick slice of the
 *              world whose section number is {@code n} (section 4 is Y 64 to 79, section -4 is Y -64 to -49).</li>
 *              </ul>
 *              Every layer other than the default one is stored in a folder of its own.
 */
public record MapView(RegistryKey<World> dimension, String layer) {
	public static final String DEFAULT = "default";
	public static final String ROOF = "roof";
	private static final String SLICE_PREFIX = "y";

	public static MapView defaultOf(RegistryKey<World> dimension) {
		return new MapView(dimension, DEFAULT);
	}

	public boolean isDefault() {
		return DEFAULT.equals(layer);
	}

	public boolean isRoof() {
		return ROOF.equals(layer);
	}

	public boolean isSlice() {
		return sliceSection(layer) != null;
	}

	/** The section number of a slice view; only valid if {@link #isSlice()}. */
	public int section() {
		Integer section = sliceSection(layer);
		return section == null ? 0 : section;
	}

	/** The layer id of the slice with this section number. */
	public static String sliceLayer(int section) {
		return SLICE_PREFIX + section;
	}

	/** The section number of a slice layer id, or null if it is not one. */
	public static Integer sliceSection(String layer) {
		if (layer == null || !layer.startsWith(SLICE_PREFIX) || layer.length() == SLICE_PREFIX.length()) {
			return null;
		}
		try {
			return Integer.parseInt(layer.substring(SLICE_PREFIX.length()));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** The highest block of a slice: looking down from here is what the slice view shows. */
	public static int sliceTopY(int section) {
		return section * 16 + 15;
	}
}
