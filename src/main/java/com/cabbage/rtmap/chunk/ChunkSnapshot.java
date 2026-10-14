package com.cabbage.rtmap.chunk;

import net.minecraft.network.PacketByteBuf;

/**
 * What the server knows about a rectangle of chunks at one moment: how each chunk is loaded and why, and
 * (optionally) how many entities, block entities and hoppers it holds. The same class is built by the server,
 * sent over the wire, and read by the client; in singleplayer it is handed over without being serialised.
 */
public final class ChunkSnapshot {
	/** Low two bits of a state byte: how far the chunk is processed. */
	public static final int LEVEL_MASK = 3;
	public static final int LEVEL_NONE = 0;
	/** Border chunk: loaded, but neither blocks nor entities tick. */
	public static final int LEVEL_BORDER = 1;
	/** Block ticking: redstone, block entities and hoppers run, entities do not. */
	public static final int LEVEL_BLOCK_TICKING = 2;
	public static final int LEVEL_ENTITY_TICKING = 3;

	/** Other bits of a state byte: the kinds of tickets that currently hold the chunk. */
	public static final int TICKET_PLAYER = 1 << 2;
	public static final int TICKET_PORTAL = 1 << 3;
	public static final int TICKET_FORCED = 1 << 4;
	public static final int TICKET_START = 1 << 5;
	public static final int TICKET_DRAGON = 1 << 6;
	public static final int TICKET_OTHER = 1 << 7;

	/** Counts are clamped so each fits in 16 bits. */
	public static final int MAX_COUNT = 0xFFFF;

	private static final int MAX_DIMENSION_LENGTH = 128;

	private final String dimension;
	private final int minChunkX;
	private final int minChunkZ;
	private final int width;
	private final int height;
	private final byte[] state;
	private final char[] entities;
	private final char[] blockEntities;
	private final char[] hoppers;

	/** {@code state} is null when only load data was asked for; the three count arrays are null when only state was. */
	public ChunkSnapshot(String dimension, int minChunkX, int minChunkZ, int width, int height, byte[] state,
		char[] entities, char[] blockEntities, char[] hoppers) {
		this.dimension = dimension;
		this.minChunkX = minChunkX;
		this.minChunkZ = minChunkZ;
		this.width = width;
		this.height = height;
		this.state = state;
		this.entities = entities;
		this.blockEntities = blockEntities;
		this.hoppers = hoppers;
	}

	public String dimension() {
		return dimension;
	}

	public int minChunkX() {
		return minChunkX;
	}

	public int minChunkZ() {
		return minChunkZ;
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	public boolean hasState() {
		return state != null;
	}

	public boolean hasLoad() {
		return entities != null;
	}

	public boolean contains(int chunkX, int chunkZ) {
		return chunkX >= minChunkX && chunkX < minChunkX + width && chunkZ >= minChunkZ && chunkZ < minChunkZ + height;
	}

	private int index(int chunkX, int chunkZ) {
		return (chunkZ - minChunkZ) * width + (chunkX - minChunkX);
	}

	/** The raw state byte as 0..255, or 0 (not loaded) when this snapshot has no state or does not cover the chunk. */
	public int state(int chunkX, int chunkZ) {
		return state == null || !contains(chunkX, chunkZ) ? 0 : state[index(chunkX, chunkZ)] & 0xFF;
	}

	public int level(int chunkX, int chunkZ) {
		return state(chunkX, chunkZ) & LEVEL_MASK;
	}

	public int entities(int chunkX, int chunkZ) {
		return entities == null || !contains(chunkX, chunkZ) ? 0 : entities[index(chunkX, chunkZ)];
	}

	public int blockEntities(int chunkX, int chunkZ) {
		return blockEntities == null || !contains(chunkX, chunkZ) ? 0 : blockEntities[index(chunkX, chunkZ)];
	}

	public int hoppers(int chunkX, int chunkZ) {
		return hoppers == null || !contains(chunkX, chunkZ) ? 0 : hoppers[index(chunkX, chunkZ)];
	}

	public void write(PacketByteBuf buf) {
		buf.writeString(dimension, MAX_DIMENSION_LENGTH);
		buf.writeInt(minChunkX);
		buf.writeInt(minChunkZ);
		buf.writeVarInt(width);
		buf.writeVarInt(height);
		buf.writeByte((hasState() ? 1 : 0) | (hasLoad() ? 2 : 0));

		if (state != null) {
			buf.writeByteArray(state);
		}
		if (entities != null) {
			// Most chunks hold nothing worth counting, so only the non-empty ones are sent, as gaps between indexes.
			int count = 0;
			for (int i = 0; i < entities.length; i++) {
				if (entities[i] != 0 || blockEntities[i] != 0 || hoppers[i] != 0) {
					count++;
				}
			}
			buf.writeVarInt(count);
			int previous = -1;
			for (int i = 0; i < entities.length; i++) {
				if (entities[i] != 0 || blockEntities[i] != 0 || hoppers[i] != 0) {
					buf.writeVarInt(i - previous - 1);
					buf.writeVarInt(entities[i]);
					buf.writeVarInt(blockEntities[i]);
					buf.writeVarInt(hoppers[i]);
					previous = i;
				}
			}
		}
	}

	/** Reads a snapshot, throwing if any size is outside what a query may ask for. */
	public static ChunkSnapshot read(PacketByteBuf buf, int maxSide, int maxChunks) {
		String dimension = buf.readString(MAX_DIMENSION_LENGTH);
		int minX = buf.readInt();
		int minZ = buf.readInt();
		int width = buf.readVarInt();
		int height = buf.readVarInt();
		if (width < 1 || height < 1 || width > maxSide || height > maxSide || (long) width * height > maxChunks) {
			throw new IllegalArgumentException("bad snapshot size " + width + "x" + height);
		}
		int area = width * height;
		int what = buf.readUnsignedByte();

		byte[] state = null;
		if ((what & 1) != 0) {
			state = buf.readByteArray(area);
			if (state.length != area) {
				throw new IllegalArgumentException("state length " + state.length + " != " + area);
			}
		}
		char[] entities = null;
		char[] blockEntities = null;
		char[] hoppers = null;
		if ((what & 2) != 0) {
			entities = new char[area];
			blockEntities = new char[area];
			hoppers = new char[area];
			int count = buf.readVarInt();
			if (count < 0 || count > area) {
				throw new IllegalArgumentException("bad load entry count " + count);
			}
			int index = -1;
			for (int i = 0; i < count; i++) {
				index += buf.readVarInt() + 1;
				if (index < 0 || index >= area) {
					throw new IllegalArgumentException("load index out of range");
				}
				entities[index] = clamp(buf.readVarInt());
				blockEntities[index] = clamp(buf.readVarInt());
				hoppers[index] = clamp(buf.readVarInt());
			}
		}
		return new ChunkSnapshot(dimension, minX, minZ, width, height, state, entities, blockEntities, hoppers);
	}

	private static char clamp(int value) {
		return (char) Math.max(0, Math.min(MAX_COUNT, value));
	}
}
