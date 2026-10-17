package com.cabbage.rtmap.client.perf;

import com.cabbage.rtmap.RTMap;
import com.cabbage.rtmap.client.network.ClientSession;
import com.cabbage.rtmap.network.RTMapChannels;
import com.cabbage.rtmap.server.ServerPerformance;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;

/**
 * The server's recent tick time (MSPT), for the full-screen map's status line. The map says it wants the number
 * while it is open; this class asks every couple of seconds, from the integrated server directly in singleplayer
 * or through {@code mspt_query} / {@code mspt_data} on a server that granted it.
 */
public final class ServerPerformanceClient {
	private static final long REFRESH_MS = 2000;
	private static final long WANT_WINDOW_MS = 500;
	private static final long BACKOFF_MS = 5000;
	/** A reading older than this is not shown any more. */
	private static final long STALE_MS = 6000;

	private static long wantedMs;
	private static long nextAskMs;
	private static long receivedMs;
	private static boolean pending;
	private static long sentMs;
	private static float averageMs;
	private static float worstMs;

	private ServerPerformanceClient() {
	}

	public static void init() {
		ClientPlayNetworking.registerGlobalReceiver(RTMapChannels.MSPT_DATA, (client, handler, buf, sender) -> {
			try {
				int status = buf.readUnsignedByte();
				float average = status == RTMapChannels.STATUS_MSPT_OK ? buf.readFloat() : 0f;
				float worst = status == RTMapChannels.STATUS_MSPT_OK ? buf.readFloat() : 0f;
				client.execute(() -> onResponse(status, average, worst));
			} catch (RuntimeException e) {
				RTMap.LOGGER.warn("Malformed mspt_data", e);
				client.execute(() -> pending = false);
			}
		});
		ClientTickEvents.END_CLIENT_TICK.register(ServerPerformanceClient::tick);
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reset());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
	}

	private static void reset() {
		pending = false;
		receivedMs = 0;
		wantedMs = 0;
		nextAskMs = 0;
	}

	public static boolean available() {
		MinecraftClient client = MinecraftClient.getInstance();
		return client.isIntegratedServerRunning()
			|| (ClientSession.grants().contains(RTMapChannels.FEATURE_MSPT) && ClientPlayNetworking.canSend(RTMapChannels.MSPT_QUERY));
	}

	/** Called every frame the number is on screen. */
	public static void want() {
		wantedMs = Util.getMeasuringTimeMs();
	}

	/** Average of the last 100 server ticks in milliseconds, or -1 if there is no fresh reading. */
	public static float averageMs() {
		return fresh() ? averageMs : -1f;
	}

	/** The longest of the last 100 server ticks in milliseconds, or -1 if there is no fresh reading. */
	public static float worstMs() {
		return fresh() ? worstMs : -1f;
	}

	private static boolean fresh() {
		return receivedMs != 0 && Util.getMeasuringTimeMs() - receivedMs < STALE_MS;
	}

	private static void tick(MinecraftClient client) {
		long now = Util.getMeasuringTimeMs();
		if (pending && now - sentMs > BACKOFF_MS) {
			pending = false;
		}
		if (now - wantedMs > WANT_WINDOW_MS || pending || now < nextAskMs || client.world == null || !available()) {
			return;
		}
		pending = true;
		sentMs = now;
		nextAskMs = now + REFRESH_MS;

		if (client.isIntegratedServerRunning()) {
			MinecraftServer server = client.getServer();
			if (server == null) {
				pending = false;
				return;
			}
			server.submit(() -> ServerPerformance.recentTickMillis(server)).whenComplete((millis, error) -> client.execute(() -> {
				if (millis != null) {
					onResponse(RTMapChannels.STATUS_MSPT_OK, millis[0], millis[1]);
				} else {
					RTMap.LOGGER.warn("Could not read the server tick time", error);
					pending = false;
					nextAskMs = Util.getMeasuringTimeMs() + BACKOFF_MS;
				}
			}));
			return;
		}
		ClientPlayNetworking.send(RTMapChannels.MSPT_QUERY, PacketByteBufs.create());
	}

	private static void onResponse(int status, float average, float worst) {
		pending = false;
		if (status == RTMapChannels.STATUS_MSPT_OK) {
			averageMs = average;
			worstMs = worst;
			receivedMs = Util.getMeasuringTimeMs();
		} else {
			nextAskMs = Util.getMeasuringTimeMs() + BACKOFF_MS;
		}
	}
}
