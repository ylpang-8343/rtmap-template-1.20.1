package com.cabbage.rtmap.network;

import java.util.ArrayList;
import java.util.List;

import com.cabbage.rtmap.RTMap;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

/** Channel ids, protocol constants and size limits. See docs/network-protocol.en.md. */
public final class RTMapChannels {
	public static final Identifier CLIENT_HELLO = RTMap.id("client_hello");
	public static final Identifier SERVER_HELLO = RTMap.id("server_hello");
	public static final Identifier PERMISSIONS = RTMap.id("permissions");
	public static final Identifier SEED_REQUEST = RTMap.id("seed_request");
	public static final Identifier SEED_RESPONSE = RTMap.id("seed_response");
	public static final Identifier CHUNK_QUERY = RTMap.id("chunk_query");
	public static final Identifier CHUNK_DATA = RTMap.id("chunk_data");
	public static final Identifier MSPT_QUERY = RTMap.id("mspt_query");
	public static final Identifier MSPT_DATA = RTMap.id("mspt_data");
	public static final Identifier PROFILE_CONTROL = RTMap.id("profile_control");
	public static final Identifier PROFILE_STATE = RTMap.id("profile_state");
	public static final Identifier PROFILE_RESULT = RTMap.id("profile_result");

	public static final int PROTOCOL_VERSION = 1;
	public static final int MIN_PROTOCOL_VERSION = 1;

	public static final String FEATURE_SEED = "seed";
	/** Per-chunk load level and tickets (which chunks are loaded and why). */
	public static final String FEATURE_CHUNK_STATE = "chunk_state";
	/** Per-chunk entity, block entity and hopper counts (the load heat map). */
	public static final String FEATURE_CHUNK_LOAD = "chunk_load";
	/** How long the server's recent ticks took (MSPT). */
	public static final String FEATURE_MSPT = "mspt";
	/** Starting a profiling run that measures how much tick time each chunk uses. Ops only. */
	public static final String FEATURE_CHUNK_TIMING = "chunk_timing";

	/** Bits in the {@code what} byte of a chunk_query. */
	public static final int QUERY_STATE = 1;
	public static final int QUERY_LOAD = 2;

	public static final int STATUS_CHUNK_OK = 0;
	public static final int STATUS_CHUNK_NOT_AUTHORIZED = 1;
	public static final int STATUS_CHUNK_RATE_LIMITED = 2;
	public static final int STATUS_CHUNK_BAD_REQUEST = 3;
	public static final int STATUS_CHUNK_WRONG_DIMENSION = 4;

	public static final int STATUS_MSPT_OK = 0;
	public static final int STATUS_MSPT_NOT_AUTHORIZED = 1;
	public static final int STATUS_MSPT_RATE_LIMITED = 2;

	/** The {@code action} byte of a profile_control. */
	public static final int PROFILE_ACTION_START = 1;
	public static final int PROFILE_ACTION_STOP = 2;

	/** The {@code state} byte of a profile_state. */
	public static final int PROFILE_STATE_IDLE = 0;
	public static final int PROFILE_STATE_RUNNING = 1;
	public static final int PROFILE_STATE_DENIED = 2;

	/** Why a start was refused. */
	public static final int PROFILE_REASON_NOT_AUTHORIZED = 1;
	public static final int PROFILE_REASON_BUSY = 2;
	public static final int PROFILE_REASON_DISABLED = 3;
	public static final int PROFILE_REASON_RATE_LIMITED = 4;
	public static final int PROFILE_REASON_BAD_REQUEST = 5;

	/** A query asks for at most this many chunks along each side, and this many in total. */
	public static final int MAX_QUERY_SIDE = 256;
	public static final int MAX_QUERY_CHUNKS = 65536;
	/** Upper bound the client accepts for a chunk_data packet. */
	public static final int MAX_CHUNK_DATA_BYTES = 1 << 20;

	public static final int STATUS_HELLO_OK = 0;
	public static final int STATUS_HELLO_INCOMPATIBLE = 1;

	public static final int STATUS_SEED_OK = 0;
	public static final int STATUS_SEED_NOT_AUTHORIZED = 1;
	public static final int STATUS_SEED_FEATURE_DISABLED = 2;
	public static final int STATUS_SEED_RATE_LIMITED = 3;

	public static final int MAX_PACKET_BYTES = 4096;
	public static final int MAX_FEATURES = 64;
	public static final int MAX_STRING_LENGTH = 64;

	private RTMapChannels() {
	}

	public static void writeStringList(PacketByteBuf buf, Iterable<String> values, int size) {
		buf.writeVarInt(size);
		for (String value : values) {
			buf.writeString(value, MAX_STRING_LENGTH);
		}
	}

	/** Reads a bounded list of bounded strings; throws if the sender exceeds the limits. */
	public static List<String> readStringList(PacketByteBuf buf) {
		int size = buf.readVarInt();
		if (size < 0 || size > MAX_FEATURES) {
			throw new IllegalArgumentException("string list too long: " + size);
		}
		List<String> values = new ArrayList<>(size);
		for (int i = 0; i < size; i++) {
			values.add(buf.readString(MAX_STRING_LENGTH));
		}
		return values;
	}
}
