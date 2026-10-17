package com.cabbage.rtmap.server;

import net.minecraft.server.MinecraftServer;

/** How long the server's recent ticks took. Reads a counter the game already keeps; it measures nothing itself. */
public final class ServerPerformance {
	private static final double NANOS_PER_MILLI = 1_000_000.0;

	private ServerPerformance() {
	}

	/** {average, worst} of the last 100 ticks, in milliseconds. */
	public static float[] recentTickMillis(MinecraftServer server) {
		long total = 0;
		long worst = 0;
		long[] lengths = server.lastTickLengths;
		for (long length : lengths) {
			total += length;
			worst = Math.max(worst, length);
		}
		return new float[] {(float) (total / (double) lengths.length / NANOS_PER_MILLI), (float) (worst / NANOS_PER_MILLI)};
	}
}
