package com.cabbage.rtmap.client.map.layer;

import net.minecraft.client.world.ClientWorld;

/**
 * What a layer is drawn into. Layers draw in the view's own coordinates: the origin is the middle of the view,
 * x grows to the right and y grows downwards, in screen pixels. If the view is rotated, the rotation is already
 * applied, so a layer never has to think about it.
 *
 * <p>The extents are conservative (a rotated view needs a larger area than it shows), so anything inside them may
 * be drawn and clipped by the caller.
 */
public record LayerView(
	ClientWorld world,
	double centerX,
	double centerZ,
	/** Screen pixels per block. */
	double zoom,
	/** Half the visible width and height, in blocks. */
	double halfWidthBlocks,
	double halfHeightBlocks,
	boolean minimap,
	/** How far the view has been turned, in degrees; a layer undoes it to keep icons and text upright. */
	float rotationDegrees
) {
	/** View x (pixels) of a world x coordinate. */
	public double x(double blockX) {
		return (blockX - centerX) * zoom;
	}

	/** View y (pixels) of a world z coordinate. */
	public double y(double blockZ) {
		return (blockZ - centerZ) * zoom;
	}

	public double leftBlock() {
		return centerX - halfWidthBlocks;
	}

	public double rightBlock() {
		return centerX + halfWidthBlocks;
	}

	public double topBlock() {
		return centerZ - halfHeightBlocks;
	}

	public double bottomBlock() {
		return centerZ + halfHeightBlocks;
	}
}
