package com.cabbage.rtmap.server;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Sliding-window limiter keyed by player UUID. State lives only on the server. */
public final class SeedRateLimiter {
	private static final Map<UUID, Deque<Long>> REQUESTS = new ConcurrentHashMap<>();

	private SeedRateLimiter() {
	}

	/** Records an attempt and returns whether it is within the limit. */
	public static boolean tryAcquire(UUID player, int limit, int windowSeconds) {
		long now = System.nanoTime();
		long windowNanos = windowSeconds * 1_000_000_000L;
		Deque<Long> times = REQUESTS.computeIfAbsent(player, id -> new ArrayDeque<>());
		synchronized (times) {
			while (!times.isEmpty() && now - times.peekFirst() >= windowNanos) {
				times.pollFirst();
			}
			if (times.size() >= limit) {
				return false;
			}
			times.addLast(now);
			return true;
		}
	}

	public static void forget(UUID player) {
		REQUESTS.remove(player);
	}
}
