package com.cabbage.rtmap.client.network;

import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

/**
 * What the client currently knows about the server it is connected to. Held in memory only:
 * a server-provided seed is never written to disk, because it is only usable while the server still grants it.
 */
public final class ClientSession {
	public enum SeedSource {
		/** Read from the integrated server (singleplayer or own LAN world). */
		LOCAL,
		/** Delivered by a dedicated server that granted it. */
		SERVER
	}

	private static volatile Long seed;
	private static volatile SeedSource seedSource;
	private static volatile Set<String> grants = Set.of();
	private static volatile UUID worldId;
	private static volatile boolean helloSent;
	private static volatile boolean seedRequestPending;

	private ClientSession() {
	}

	/** The seed to use for seed-based features such as slime chunks, if one is available. */
	public static OptionalLong seed() {
		Long value = seed;
		return value == null ? OptionalLong.empty() : OptionalLong.of(value);
	}

	public static SeedSource seedSource() {
		return seedSource;
	}

	public static UUID worldId() {
		return worldId;
	}

	public static Set<String> grants() {
		return grants;
	}

	static boolean helloSent() {
		return helloSent;
	}

	static void markHelloSent() {
		helloSent = true;
	}

	static boolean seedRequestPending() {
		return seedRequestPending;
	}

	static void markSeedRequestPending(boolean pending) {
		seedRequestPending = pending;
	}

	static void setSeed(long value, SeedSource source) {
		seed = value;
		seedSource = source;
	}

	/** Drops a server-provided seed (revocation). A local seed is not affected. */
	static void clearServerSeed() {
		if (seedSource == SeedSource.SERVER) {
			seed = null;
			seedSource = null;
		}
	}

	static void setHandshake(UUID id, Set<String> granted) {
		worldId = id;
		grants = granted;
	}

	static void setGrants(Set<String> granted) {
		grants = granted;
	}

	static void reset() {
		seed = null;
		seedSource = null;
		grants = Set.of();
		worldId = null;
		helloSent = false;
		seedRequestPending = false;
	}
}
