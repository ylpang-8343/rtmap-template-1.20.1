package com.cabbage.rtmap.client.map;

import java.util.HashSet;
import java.util.Set;

import com.cabbage.rtmap.client.config.ClientConfig;
import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Which {@link MapView} the player is looking at in the dimension they are in, shared by the full-screen map and the
 * minimap. The player can choose a layer (remembered per dimension), or let the map follow them: underground it
 * shows the slice they are in, on the surface the surface, and above the Nether's roof the roof.
 */
public final class MapViews {
	/** The follow mode only changes layer after the wish has been the same for this many ticks, so stairs do not flicker it. */
	private static final int FOLLOW_DEBOUNCE_TICKS = 10;
	/** Underground means a solid roof over the player and the columns next to them, no further up than this. */
	private static final int ROOF_SCAN_BLOCKS = 32;
	/** The roof is looked for every few ticks, not every tick. */
	private static final int ROOF_CHECK_TICKS = 5;
	private static final long DISK_REFRESH_MS = 3000;

	private static String followLayer;
	private static String wishedLayer;
	private static int wishedTicks;

	private static int roofCheckCooldown;
	private static boolean underground;

	private static Set<String> layersOnDisk = Set.of();
	private static long layersCheckedMs;

	private MapViews() {
	}

	/** Called every client tick: works out where the follow mode wants to be. */
	public static void tick(MinecraftClient client) {
		ClientWorld world = client.world;
		ClientPlayerEntity player = client.player;
		if (world == null || player == null || !ClientConfig.followLayer()) {
			followLayer = null;
			wishedLayer = null;
			return;
		}

		String wanted = wantedLayer(world, player);
		if (followLayer == null) {
			followLayer = wanted;
		} else if (wanted.equals(followLayer)) {
			wishedLayer = null;
		} else if (wanted.equals(wishedLayer)) {
			if (++wishedTicks >= FOLLOW_DEBOUNCE_TICKS) {
				followLayer = wanted;
				wishedLayer = null;
			}
		} else {
			wishedLayer = wanted;
			wishedTicks = 1;
		}
	}

	private static String wantedLayer(ClientWorld world, ClientPlayerEntity player) {
		if (world.getDimension().hasCeiling()) {
			// A dimension with a roof has no surface to follow: below the roof it is the slice you are in, above it the roof.
			if (player.getBlockY() >= roofTopY(world) + 1) {
				return MapView.ROOF;
			}
			return MapView.sliceLayer(sectionOf(world, player.getBlockY()));
		}
		return isUnderground(world, player) ? MapView.sliceLayer(sectionOf(world, player.getBlockY())) : MapView.DEFAULT;
	}

	/** Whether there is a solid roof over the player and the columns next to them, as under a cliff or in a cave. */
	private static boolean isUnderground(ClientWorld world, ClientPlayerEntity player) {
		if (roofCheckCooldown-- > 0) {
			return underground;
		}
		roofCheckCooldown = ROOF_CHECK_TICKS - 1;
		underground = hasRoof(world, player);
		return underground;
	}

	/**
	 * Looks up from just above the player's head in each of the 3x3 columns around them for a solid block that is not
	 * leaves (so a tree is not a roof). The server only sends the client some height maps, and not the one that skips
	 * leaves, so the blocks themselves are looked at; the synced one tells where it is pointless to look.
	 */
	private static boolean hasRoof(ClientWorld world, ClientPlayerEntity player) {
		int fromY = player.getBlockY() + 2;
		BlockPos.Mutable pos = new BlockPos.Mutable();
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				int x = player.getBlockX() + dx;
				int z = player.getBlockZ() + dz;
				int top = world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);
				if (top <= fromY) {
					return false; // nothing blocks the way up here
				}
				WorldChunk chunk = world.getChunkManager().getWorldChunk(x >> 4, z >> 4);
				if (chunk == null) {
					return false;
				}
				boolean roof = false;
				int limit = Math.min(top, Math.min(world.getTopY(), fromY + ROOF_SCAN_BLOCKS));
				for (int y = fromY; y < limit && !roof; y++) {
					BlockState state = chunk.getBlockState(pos.set(x, y, z));
					roof = !state.isAir() && state.getFluidState().isEmpty() && !state.isIn(BlockTags.LEAVES)
						&& state.getMapColor(world, pos) != MapColor.CLEAR;
				}
				if (!roof) {
					return false;
				}
			}
		}
		return true;
	}

	/** The layer being shown for this world's dimension. */
	public static MapView current(ClientWorld world) {
		String layer = ClientConfig.followLayer() && followLayer != null
			? followLayer : ClientConfig.layerChoice(world.getRegistryKey().getValue().toString());
		return new MapView(world.getRegistryKey(), valid(world, layer));
	}

	/** Falls back to the default layer if this one does not exist in the dimension. */
	private static String valid(ClientWorld world, String layer) {
		if (MapView.ROOF.equals(layer)) {
			return world.getDimension().hasCeiling() ? layer : MapView.DEFAULT;
		}
		Integer section = MapView.sliceSection(layer);
		if (section != null) {
			return section >= bottomSection(world) && section <= topSection(world) ? layer : MapView.DEFAULT;
		}
		return MapView.DEFAULT;
	}

	/** The highest block that belongs to the dimension's own space; in a dimension with a roof, the top of the roof. */
	public static int roofTopY(ClientWorld world) {
		return Math.min(world.getTopY(), world.getDimension().minY() + world.getDimension().logicalHeight()) - 1;
	}

	public static int bottomSection(ClientWorld world) {
		return Math.floorDiv(world.getBottomY(), 16);
	}

	public static int topSection(ClientWorld world) {
		return Math.floorDiv(roofTopY(world), 16);
	}

	public static int sectionOf(ClientWorld world, int y) {
		return MathHelper.clamp(Math.floorDiv(y, 16), bottomSection(world), topSection(world));
	}

	/** Chooses a layer for the dimension the player is in. This ends the follow mode, since the player has decided. */
	public static void choose(ClientWorld world, String layer) {
		ClientConfig.setFollowLayer(false);
		ClientConfig.setLayerChoice(world.getRegistryKey().getValue().toString(), valid(world, layer));
	}

	public static void setFollow(boolean follow) {
		if (follow) {
			// Start from where the player is, not from the old choice, so the map does not flash through it.
			followLayer = null;
		} else {
			// Keep showing what the follow mode was showing, as the player's own choice.
			ClientWorld world = MinecraftClient.getInstance().world;
			if (world != null) {
				ClientConfig.setLayerChoice(world.getRegistryKey().getValue().toString(), current(world).layer());
			}
		}
		ClientConfig.setFollowLayer(follow);
	}

	/** The section the player is in, for marking it on the layer strip. */
	public static int playerSection(ClientWorld world, ClientPlayerEntity player) {
		return sectionOf(world, player.getBlockY());
	}

	/** Layers of this dimension that have tiles saved, refreshed every few seconds. The default layer is not listed. */
	public static Set<String> layersWithData(ClientWorld world) {
		long now = net.minecraft.util.Util.getMeasuringTimeMs();
		if (now - layersCheckedMs > DISK_REFRESH_MS) {
			layersCheckedMs = now;
			layersOnDisk = new HashSet<>(MapStorage.layersOnDisk(world.getRegistryKey()));
		}
		return layersOnDisk;
	}

	/** "Y 64 to 79" for a slice; the name of the layer otherwise. */
	public static Text label(MapView view, ClientWorld world) {
		if (view.isSlice()) {
			return Text.translatable("view.rtmap.slice", view.section() * 16, view.section() * 16 + 15);
		}
		if (view.isRoof()) {
			return Text.translatable("view.rtmap.roof");
		}
		return Text.translatable(world.getDimension().hasCeiling() ? "view.rtmap.below_roof" : "view.rtmap.surface");
	}

	/** A few characters for the minimap, or an empty text for the default layer. */
	public static Text shortLabel(MapView view) {
		if (view.isSlice()) {
			return Text.literal("Y " + view.section() * 16);
		}
		if (view.isRoof()) {
			return Text.translatable("view.rtmap.roof_short");
		}
		return Text.empty();
	}
}
