package com.cabbage.rtmap.client.map;

import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Turns the surface of one chunk into 16x16 map pixels, using vanilla map colors and the same height and
 * water-depth shading as filled maps.
 */
public final class ChunkSampler {
	private ChunkSampler() {
	}

	public static void sample(ClientWorld world, WorldChunk chunk, MapRegion region) {
		ChunkPos chunkPos = chunk.getPos();
		int baseX = chunkPos.getStartX();
		int baseZ = chunkPos.getStartZ();
		int pixelX = Math.floorMod(baseX, MapRegion.SIZE);
		int pixelZ = Math.floorMod(baseZ, MapRegion.SIZE);

		Heightmap heightmap = chunk.getHeightmap(Heightmap.Type.WORLD_SURFACE);
		int bottom = world.getBottomY();
		BlockPos.Mutable pos = new BlockPos.Mutable();

		// First pass: find the surface block of every column.
		int[] heights = new int[256];
		MapColor[] colors = new MapColor[256];
		int[] waterDepths = new int[256];
		for (int dz = 0; dz < 16; dz++) {
			for (int dx = 0; dx < 16; dx++) {
				int i = dz * 16 + dx;
				int x = baseX + dx;
				int z = baseZ + dz;

				// The heightmap points at the first free block above the surface.
				int y = heightmap.get(dx, dz) - 1;
				BlockState state = null;
				MapColor color = MapColor.CLEAR;
				while (y >= bottom) {
					state = chunk.getBlockState(pos.set(x, y, z));
					color = state.getMapColor(world, pos);
					if (color != MapColor.CLEAR) {
						break;
					}
					y--;
				}
				if (y < bottom) {
					continue; // nothing here (void)
				}

				heights[i] = y;
				if (state.getFluidState().isIn(FluidTags.WATER)) {
					int depth = 1;
					int floor = y;
					while (floor > bottom) {
						FluidState below = chunk.getFluidState(pos.set(x, floor - 1, z));
						if (!below.isIn(FluidTags.WATER)) {
							break;
						}
						floor--;
						depth++;
					}
					waterDepths[i] = depth;
					color = MapColor.WATER_BLUE;
				}
				colors[i] = color;
			}
		}

		// Row of heights just north of this chunk, for shading the first row of pixels.
		Chunk north = world.getChunk(chunkPos.x, chunkPos.z - 1, ChunkStatus.FULL, false);

		// Second pass: shade by slope (land) or depth (water).
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
				if (waterDepths[i] > 0) {
					double shade = waterDepths[i] * 0.1 + parity * 0.2;
					brightness = shade < 0.5 ? MapColor.Brightness.HIGH
						: shade > 0.9 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
				} else {
					int northHeight;
					if (dz > 0) {
						northHeight = heights[i - 16];
					} else if (north != null) {
						northHeight = north.getHeightmap(Heightmap.Type.WORLD_SURFACE).get(dx, 15) - 1;
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
}
