package com.cabbage.rtmap.client.map;

import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
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
 */
public final class ChunkSampler {
	private ChunkSampler() {
	}

	/**
	 * @param belowRoof for a dimension with a roof: show the floor of its highest open space (true) or the top, roof
	 *                  included (false). Ignored in dimensions without a roof.
	 */
	public static void sample(ClientWorld world, WorldChunk chunk, MapRegion region, boolean belowRoof) {
		ChunkPos chunkPos = chunk.getPos();
		int baseX = chunkPos.getStartX();
		int baseZ = chunkPos.getStartZ();
		int pixelX = Math.floorMod(baseX, MapRegion.SIZE);
		int pixelZ = Math.floorMod(baseZ, MapRegion.SIZE);

		boolean roofed = belowRoof && world.getDimension().hasCeiling();
		int bottom = world.getBottomY();
		// The highest block of the part of the world that counts: the roof's top for a roofed dimension.
		int startY = Math.min(world.getTopY(), world.getDimension().minY() + world.getDimension().logicalHeight()) - 1;
		Heightmap heightmap = chunk.getHeightmap(Heightmap.Type.WORLD_SURFACE);
		BlockPos.Mutable pos = new BlockPos.Mutable();

		// First pass: find the surface block of every column.
		int[] heights = new int[256];
		MapColor[] colors = new MapColor[256];
		int[] fluidDepths = new int[256];
		for (int dz = 0; dz < 16; dz++) {
			for (int dx = 0; dx < 16; dx++) {
				int i = dz * 16 + dx;
				int x = baseX + dx;
				int z = baseZ + dz;

				int y = roofed
					? roofedSurfaceY(world, chunk, x, z, startY, bottom, pos)
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
						northHeight = northNeighborHeight(world, north, baseX + dx, baseZ - 1, dx, roofed, startY, bottom, pos);
					} else {
						northHeight = heights[i];
					}
					double shade = (heights[i] - northHeight) * 4.0 / 5.0 + (parity - 0.5) * 0.4;
					brightness = shade > 0.6 ? MapColor.Brightness.HIGH
						: shade < -0.6 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
				}
				region.setPixel(pixelX + dx, pixelZ + dz, color.getRenderColor(brightness));
			}
		}
	}

	/** Height of the surface of the last row of the chunk to the north, for shading across the chunk border. */
	private static int northNeighborHeight(ClientWorld world, Chunk north, int x, int z, int dx, boolean roofed, int startY,
		int bottom, BlockPos.Mutable pos) {
		if (roofed) {
			int y = roofedSurfaceY(world, north, x, z, startY, bottom, pos);
			return y < bottom ? bottom : y;
		}
		return north.getHeightmap(Heightmap.Type.WORLD_SURFACE).get(dx, 15) - 1;
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

	/**
	 * The floor of the highest open space below {@code startY}: skip down through solid blocks (the roof) to the
	 * first air, then down through air to the first solid block. If there is no air at all the column is solid and
	 * its top block is the surface. Returns a value below {@code bottom} if there is only air.
	 */
	private static int roofedSurfaceY(ClientWorld world, Chunk chunk, int x, int z, int startY, int bottom, BlockPos.Mutable pos) {
		int y = startY;
		while (y >= bottom) {
			BlockState state = chunk.getBlockState(pos.set(x, y, z));
			if (state.getMapColor(world, pos) == MapColor.CLEAR) {
				break;
			}
			y--;
		}
		if (y < bottom) {
			return startY; // solid from top to bottom
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
