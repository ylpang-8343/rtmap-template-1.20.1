package com.cabbage.rtmap.client.map;

import java.util.concurrent.atomic.AtomicInteger;

import com.cabbage.rtmap.RTMap;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

/**
 * One square of the map, {@value #SIZE} blocks on a side (32x32 chunks), with one pixel per block.
 * Pixels are written on the client thread and uploaded to the GPU only when the region is drawn.
 */
public final class MapRegion {
	public static final int SIZE = 512;

	private static final AtomicInteger NEXT_ID = new AtomicInteger();

	private final NativeImage image = new NativeImage(NativeImage.Format.RGBA, SIZE, SIZE, true);
	private final NativeImageBackedTexture texture = new NativeImageBackedTexture(image);
	private final Identifier id = RTMap.id("map/region_" + NEXT_ID.incrementAndGet());
	/** Pixels changed since the last GPU upload. */
	private boolean dirty;
	/** Pixels changed since the last save to disk. */
	private boolean unsaved;
	/** Used to pick the least recently used region when memory is full. */
	private long lastUsed;
	private boolean closed;

	public MapRegion() {
		MinecraftClient.getInstance().getTextureManager().registerTexture(id, texture);
		// Crisp pixels, no blur and no mipmaps.
		texture.setFilter(false, false);
	}

	public Identifier id() {
		return id;
	}

	/** Sets one pixel. The color is ABGR, which is what {@link net.minecraft.block.MapColor#getRenderColor} returns. */
	public void setPixel(int x, int z, int abgr) {
		if (image.getColor(x, z) != abgr) {
			image.setColor(x, z, abgr);
			dirty = true;
			unsaved = true;
		}
	}

	/** Replaces every pixel with data read from disk. The region counts as saved afterwards. */
	public void load(int[] pixels) {
		for (int z = 0; z < SIZE; z++) {
			for (int x = 0; x < SIZE; x++) {
				image.setColor(x, z, pixels[z * SIZE + x]);
			}
		}
		dirty = true;
		unsaved = false;
	}

	/** A copy of the pixels (ABGR, row by row) that is safe to hand to another thread. */
	public int[] snapshot() {
		int[] pixels = new int[SIZE * SIZE];
		for (int z = 0; z < SIZE; z++) {
			for (int x = 0; x < SIZE; x++) {
				pixels[z * SIZE + x] = image.getColor(x, z);
			}
		}
		return pixels;
	}

	/** A closed region has released its image and must not be touched again. */
	public boolean isClosed() {
		return closed;
	}

	public boolean isUnsaved() {
		return unsaved;
	}

	public void markSaved() {
		unsaved = false;
	}

	public long lastUsed() {
		return lastUsed;
	}

	public void touch(long clock) {
		lastUsed = clock;
	}

	public void uploadIfDirty() {
		if (dirty) {
			texture.upload();
			dirty = false;
		}
	}

	/** Releases the GPU texture and the native image. Must run on the render thread. */
	public void close() {
		closed = true;
		// Destroying the registered texture also closes it.
		MinecraftClient.getInstance().getTextureManager().destroyTexture(id);
	}
}
