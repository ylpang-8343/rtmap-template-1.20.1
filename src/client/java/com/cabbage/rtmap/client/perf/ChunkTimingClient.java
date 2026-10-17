package com.cabbage.rtmap.client.perf;

import java.util.UUID;

import com.cabbage.rtmap.RTMap;
import com.cabbage.rtmap.chunk.ChunkTimingResult;
import com.cabbage.rtmap.client.map.layer.MapLayers;
import com.cabbage.rtmap.client.network.ClientSession;
import com.cabbage.rtmap.network.RTMapChannels;
import com.cabbage.rtmap.server.ChunkProfiler;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;

/**
 * Starts a chunk timing run and keeps its result. In singleplayer the integrated server is asked directly; on a
 * server the request goes through {@code profile_control}, and only if the server granted {@code chunk_timing}
 * (ops only). Only one result is kept, for the last run.
 */
public final class ChunkTimingClient {
	public static final String LAYER_ID = "chunk_timing";
	/** If the server never answers a run, stop waiting this long after it should have ended. */
	private static final long LOST_GRACE_MS = 15_000;
	private static final long PROBLEM_SHOW_MS = 6_000;

	private static boolean running;
	private static long endsAtMs;
	private static ChunkTimingResult result;
	private static int problem;
	private static long problemUntilMs;

	private ChunkTimingClient() {
	}

	public static void init() {
		ClientPlayNetworking.registerGlobalReceiver(RTMapChannels.PROFILE_STATE, (client, handler, buf, sender) -> {
			try {
				int state = buf.readUnsignedByte();
				int reason = buf.readUnsignedByte();
				int seconds = buf.readVarInt();
				client.execute(() -> onState(state, reason, seconds));
			} catch (RuntimeException e) {
				RTMap.LOGGER.warn("Malformed profile_state", e);
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(RTMapChannels.PROFILE_RESULT, (client, handler, buf, sender) -> {
			if (buf.readableBytes() > RTMapChannels.MAX_CHUNK_DATA_BYTES) {
				RTMap.LOGGER.warn("Dropped oversized profile_result");
				return;
			}
			try {
				ChunkTimingResult received = ChunkTimingResult.read(buf);
				client.execute(() -> onResult(received));
			} catch (RuntimeException e) {
				RTMap.LOGGER.warn("Malformed profile_result", e);
			}
		});
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (running && Util.getMeasuringTimeMs() > endsAtMs + LOST_GRACE_MS) {
				running = false;
			}
		});
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reset());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
	}

	private static void reset() {
		running = false;
		result = null;
		problem = 0;
	}

	/** Whether a run can be started here: singleplayer, or an op on a server that has the feature switched on. */
	public static boolean available() {
		MinecraftClient client = MinecraftClient.getInstance();
		return client.isIntegratedServerRunning()
			|| (ClientSession.grants().contains(RTMapChannels.FEATURE_CHUNK_TIMING)
				&& ClientPlayNetworking.canSend(RTMapChannels.PROFILE_CONTROL));
	}

	public static boolean running() {
		return running;
	}

	/** Whole seconds until the run should be over, never below zero. */
	public static int secondsLeft() {
		return (int) Math.max(0, Math.ceil((endsAtMs - Util.getMeasuringTimeMs()) / 1000.0));
	}

	/** The last run's result, or null. */
	public static ChunkTimingResult result() {
		return result;
	}

	/** A {@code PROFILE_REASON_*} code if the last start was refused a moment ago, otherwise 0. */
	public static int problem() {
		return Util.getMeasuringTimeMs() < problemUntilMs ? problem : 0;
	}

	public static void toggle(int seconds) {
		if (running) {
			stop();
		} else {
			start(seconds);
		}
	}

	private static void start(int seconds) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || !available()) {
			return;
		}
		problem = 0;

		if (client.isIntegratedServerRunning()) {
			MinecraftServer server = client.getServer();
			if (server == null) {
				return;
			}
			UUID id = client.player.getUuid();
			server.execute(() -> {
				ChunkProfiler.Outcome outcome = ChunkProfiler.start(id, seconds,
					finished -> client.execute(() -> onResult(finished)));
				client.execute(() -> {
					if (outcome == ChunkProfiler.Outcome.STARTED) {
						onState(RTMapChannels.PROFILE_STATE_RUNNING, 0, seconds);
					} else {
						onState(RTMapChannels.PROFILE_STATE_DENIED, RTMapChannels.PROFILE_REASON_BUSY, 0);
					}
				});
			});
			return;
		}

		PacketByteBuf buf = PacketByteBufs.create();
		buf.writeByte(RTMapChannels.PROFILE_ACTION_START);
		buf.writeVarInt(seconds);
		ClientPlayNetworking.send(RTMapChannels.PROFILE_CONTROL, buf);
	}

	/** Ends the run early; the result of what was measured still arrives. */
	public static void stop() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) {
			return;
		}
		if (client.isIntegratedServerRunning()) {
			MinecraftServer server = client.getServer();
			UUID id = client.player.getUuid();
			if (server != null) {
				server.execute(() -> ChunkProfiler.stop(id));
			}
			return;
		}
		PacketByteBuf buf = PacketByteBufs.create();
		buf.writeByte(RTMapChannels.PROFILE_ACTION_STOP);
		buf.writeVarInt(0);
		ClientPlayNetworking.send(RTMapChannels.PROFILE_CONTROL, buf);
	}

	private static void onState(int state, int reason, int seconds) {
		switch (state) {
			case RTMapChannels.PROFILE_STATE_RUNNING -> {
				running = true;
				endsAtMs = Util.getMeasuringTimeMs() + seconds * 1000L;
			}
			case RTMapChannels.PROFILE_STATE_DENIED -> {
				running = false;
				problem = reason;
				problemUntilMs = Util.getMeasuringTimeMs() + PROBLEM_SHOW_MS;
			}
			default -> running = false;
		}
	}

	private static void onResult(ChunkTimingResult received) {
		result = received;
		running = false;
		// Show what was measured: switch the layer on if it was off.
		MapLayers.setEnabled(LAYER_ID, true);
	}
}
