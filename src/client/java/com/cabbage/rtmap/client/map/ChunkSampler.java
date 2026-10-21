package com.cabbage.rtmap.client.map;

import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.EmptyBlockView;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Turns one chunk into 16x16 map pixels, using vanilla map colors and the same height and water-depth shading as
 * filled maps.
 *
 * <p>In a dimension with a roof (the Nether) a plain top-down view would show only the roof, so there the map shows
 * what you would see with the roof taken off: the floor of the highest open space in each column. Solid blocks from
 * the top are skipped until the first air, and the first solid block below that air is the surface. Things built
 * on top of the roof are not shown.
 *
 * <p>A slice (a 16 block layer chosen with the layer strip) shows caves only: a column is drawn if there is open space
 * (air, something see-through, or a fluid) inside the slice, and what is drawn is the floor of that space. Solid rock
 * in the slice is left blank, because a map of what is inside solid rock would be an X-ray.
 */
public final class ChunkSampler {
	/** Marks a column with nothing to draw, so shading never compares against it. */
	private static final int NO_HEIGHT = Integer.MIN_VALUE;

	private ChunkSampler() {
	}

	/**
	 * @param view what to draw: a slice looks down from the top of its 16 blocks; the roof view and the default view
	 *             of a dimension without a roof take the top block; the default view of a dimension with a roof
	 *             looks down from the top of its space
	 */
	public static void sample(ClientWorld world, WorldChunk chunk, MapRegion region, MapView view) {
		ChunkPos chunkPos = chunk.getPos();
		int baseX = chunkPos.getStartX();
		int baseZ = chunkPos.getStartZ();
		int pixelX = Math.floorMod(baseX, MapRegion.SIZE);
		int pixelZ = Math.floorMod(baseZ, MapRegion.SIZE);

		// Whether to look down from a height (skipping the solid blocks at the top) or to take the top block as it is.
		boolean roofed = view.isSlice() || (view.isDefault() && world.getDimension().hasCeiling());
		int bottom = world.getBottomY();
		// The highest block of the part of the world that counts: the roof's top for a roofed dimension.
		int startY = view.isSlice() ? Math.min(MapView.sliceTopY(view.section()), MapViews.roofTopY(world)) : MapViews.roofTopY(world);
		// A slice only looks for open space inside its own 16 blocks; the other views may look all the way down.
		int endY = view.isSlice() ? Math.max(bottom, view.section() * 16) : bottom;
		Heightmap heightmap = chunk.getHeightmap(Heightmap.Type.WORLD_SURFACE);
		BlockPos.Mutable pos = new BlockPos.Mutable();

		// First pass: find the surface block of every column.
		int[] heights = new int[256];
		java.util.Arrays.fill(heights, NO_HEIGHT);
		// Per section of this chunk: 0 not looked at yet, 1 only solid blocks, 2 has air or other see-through blocks.
		byte[] solidSections = new byte[chunk.countVerticalSections()];
		MapColor[] colors = new MapColor[256];
		int[] fluidDepths = new int[256];
		boolean[] lava = new boolean[256];
		for (int dz = 0; dz < 16; dz++) {
			for (int dx = 0; dx < 16; dx++) {
				int i = dz * 16 + dx;
				int x = baseX + dx;
				int z = baseZ + dz;

				int y = roofed
					? roofedSurfaceY(world, chunk, x, z, startY, endY, bottom, pos, solidSections)
					// The heightmap points at the first free block above the surface.
					: openSurfaceY(world, chunk, x, z, heightmap.get(dx, dz) - 1, bottom, pos);
				if (y < bottom) {
					continue; // nothing here (void)
				}

				BlockState state = chunk.getBlockState(pos.set(x, y, z));
				MapColor color = state.getMapColor(world, pos);
				if (color == MapColor.CLEAR) {
					continue;
				}

				heights[i] = y;
				boolean water = state.getFluidState().isIn(FluidTags.WATER);
				boolean isLava = state.getFluidState().isIn(FluidTags.LAVA);
				if (water || isLava) {
					int depth = 1;
					int floor = y;
					while (floor > bottom) {
						FluidState below = chunk.getFluidState(pos.set(x, floor - 1, z));
						if (!below.isIn(water ? FluidTags.WATER : FluidTags.LAVA)) {
							break;
						}
						floor--;
						depth++;
					}
					fluidDepths[i] = depth;
					lava[i] = isLava;
					if (water) {
						color = MapColor.WATER_BLUE;
					}
				}
				colors[i] = color;
			}
		}

		// Row of heights just north of this chunk, for shading the first row of pixels.
		Chunk north = world.getChunk(chunkPos.x, chunkPos.z - 1, ChunkStatus.FULL, false);

		// Second pass: shade by slope (land) or depth (liquids).
		for (int dz = 0; dz < 16; dz++) {
			for (int dx = 0; dx < 16; dx++) {
				int i = dz * 16 + dx;
				MapColor color = colors[i];
				if (color == null) {
					region.setPixel(pixelX + dx, pixelZ + dz, 0);
					continue;
				}

				int parity = (baseX + dx + baseZ + dz) & 1;
				MapColor.Brightness brightness;
				if (fluidDepths[i] > 0) {
					double shade = fluidDepths[i] * 0.1 + parity * 0.2;
					brightness = shade < 0.5 ? MapColor.Brightness.HIGH
						: shade > 0.9 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
				} else {
					int northHeight;
					if (dz > 0) {
						northHeight = heights[i - 16];
					} else if (north != null) {
						northHeight = northNeighborHeight(world, north, baseX + dx, baseZ - 1, dx, roofed, startY, endY, bottom, pos);
					} else {
						northHeight = NO_HEIGHT;
					}
					if (northHeight == NO_HEIGHT) {
						northHeight = heights[i]; // nothing drawn next to it, so no slope to show
					}
					double shade = (heights[i] - northHeight) * 4.0 / 5.0 + (parity - 0.5) * 0.4;
					brightness = shade > 0.6 ? MapColor.Brightness.HIGH
						: shade < -0.6 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
				}
				region.setPixel(pixelX + dx, pixelZ + dz, lava[i] ? lavaColor(brightness) : color.getRenderColor(brightness));
			}
		}
	}

	/** Height of the surface of the last row of the chunk to the north, for shading across the chunk border. */
	private static int northNeighborHeight(ClientWorld world, Chunk north, int x, int z, int dx, boolean roofed, int startY,
		int endY, int bottom, BlockPos.Mutable pos) {
		if (roofed) {
			int y = roofedSurfaceY(world, north, x, z, startY, endY, bottom, pos, new byte[north.countVerticalSections()]);
			return y < bottom ? NO_HEIGHT : y;
		}
		return north.getHeightmap(Heightmap.Type.WORLD_SURFACE).get(dx, 15) - 1;
	}

	/**
	 * Lava is drawn in bright orange, shaded a little by depth like water. Vanilla's lava colour is a dark red that
	 * disappears against netherrack, and a lava sea is the thing to see from far away. The values are in the pixel
	 * format of a map tile (opaque, blue-green-red byte order), like {@link net.minecraft.block.MapColor#getRenderColor}.
	 */
	private static int lavaColor(MapColor.Brightness brightness) {
		return switch (brightness) {
			case HIGH -> 0xFF28A5FF; // 255, 165, 40
			case LOW -> 0xFF1073F0; // 240, 115, 16
			default -> 0xFF188CFF; // 255, 140, 24
		};
	}

	/** The topmost block that has a map colour, scanning down from {@code from}; below {@code bottom} if there is none. */
	private static int openSurfaceY(ClientWorld world, Chunk chunk, int x, int z, int from, int bottom, BlockPos.Mutable pos) {
		int y = from;
		while (y >= bottom) {
			BlockState state = chunk.getBlockState(pos.set(x, y, z));
			if (state.getMapColor(world, pos) != MapColor.CLEAR) {
				return y;
			}
			y--;
		}
		return y;
	}

	/** Whether a section has nothing in it that could be see-through, so every column of it counts as solid. Cached per chunk. */
	private static boolean sectionIsSolid(Chunk chunk, int index, byte[] cache) {
		if (cache[index] == 0) {
			cache[index] = (byte) (chunk.getSection(index).hasAny(ChunkSampler::mayBeSeeThrough) ? 2 : 1);
		}
		return cache[index] == 1;
	}

	/**
	 * Air, anything else without a map colour, and fluids: the blocks a cave is made of. The colour of a block only
	 * depends on its state, so no world is needed.
	 */
	private static boolean mayBeSeeThrough(BlockState state) {
		return state.isAir() || !state.getFluidState().isEmpty()
			|| state.getMapColor(EmptyBlockView.INSTANCE, BlockPos.ORIGIN) == MapColor.CLEAR;
	}

	/**
	 * The floor of the highest open space between {@code startY} and {@code endY}. Skips down through solid blocks to
	 * the first open block (air, something see-through, or a fluid), then down through the open space to the first
	 * block with a map colour, which is returned. If there is no open space in the range the column is solid there and
	 * nothing is shown: the result is below {@code bottom}.
	 */
	private static int roofedSurfaceY(ClientWorld world, Chunk chunk, int x, int z, int startY, int endY, int bottom,
		BlockPos.Mutable pos, byte[] solidSections) {
		int y = startY;
		while (y >= endY) {
			// A section made only of solid blocks is crossed in one step; most of the way down through rock is this.
			int index = chunk.getSectionIndex(y);
			if (sectionIsSolid(chunk, index, solidSections)) {
				y = ChunkSectionPos.getBlockCoord(chunk.sectionIndexToCoord(index)) - 1;
				continue;
			}
			BlockState state = chunk.getBlockState(pos.set(x, y, z));
			if (state.getMapColor(world, pos) == MapColor.CLEAR || !state.getFluidState().isEmpty()) {
				break;
			}
			y--;
		}
		if (y < endY) {
			return bottom - 1; // solid rock all the way: not a cave, so nothing to draw
		}

		while (y >= bottom) {
			// A section with nothing in it can be crossed in one step.
			ChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
			if (section.isEmpty()) {
				y = ChunkSectionPos.getBlockCoord(chunk.sectionIndexToCoord(chunk.getSectionIndex(y))) - 1;
				continue;
			}
			BlockState state = chunk.getBlockState(pos.set(x, y, z));
			if (state.getMapColor(world, pos) != MapColor.CLEAR) {
				return y;
			}
			y--;
		}
		return y;
	}
}
