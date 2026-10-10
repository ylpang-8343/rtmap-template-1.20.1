package com.cabbage.rtmap.client.portal;

/**
 * One nether portal that has been seen. {@code x, y, z} is its middle (what is shown on the map);
 * {@code anchorX, anchorY, anchorZ} is one real portal block, used to notice later that the portal is gone.
 */
public record Portal(String dimension, int x, int y, int z, int anchorX, int anchorY, int anchorZ) {
}
