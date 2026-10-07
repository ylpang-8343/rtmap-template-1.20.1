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

	public static final int PROTOCOL_VERSION = 1;
	public static final int MIN_PROTOCOL_VERSION = 1;

	public static final String FEATURE_SEED = "seed";

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
