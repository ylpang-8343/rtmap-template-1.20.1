package com.cabbage.rtmap.server;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Sliding-window limiter keyed by player UUID. State lives only on the server. */
public final class RateLimiter {
	private final Map<UUID, Deque<Long>> requests = new ConcurrentHashMap<>();

	/** Records an attempt and returns whether it is within the limit. */
	public boolean tryAcquire(UUID player, int limit, int windowSeconds) {
		long now = System.nanoTime();
		long windowNanos = windowSeconds * 1_000_000_000L;
		Deque<Long> times = requests.computeIfAbsent(player, id -> new ArrayDeque<>());
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

	public void forget(UUID player) {
		requests.remove(player);
	}
}
