package com.cabbage.rtmap.client.chunk;

import com.cabbage.rtmap.RTMap;
import com.cabbage.rtmap.chunk.ChunkSnapshot;
import com.cabbage.rtmap.client.map.layer.LayerView;
import com.cabbage.rtmap.client.network.ClientSession;
import com.cabbage.rtmap.network.RTMapChannels;
import com.cabbage.rtmap.server.ChunkDataCollector;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Util;

/**
 * Fetches chunk loading and load data for the part of the map on screen. The layers say what they want while they
 * draw; this class decides when to ask, keeps the newest answer, and drops it when the dimension changes.
 *
 * <p>In singleplayer the integrated server is asked directly. On a server the data comes through the
 * {@code chunk_query} / {@code chunk_data} channels, and only if the server granted the matching feature.
 */
public final class ChunkDataClient {
	/** Ask again at least this often while the data is on screen. */
	private static final long REFRESH_MS = 1500;
	/** Never send two queries closer than this, so panning cannot flood the server's rate limit. */
	private static final long MIN_INTERVAL_MS = 600;
	private static final long PENDING_TIMEOUT_MS = 5000;
	private static final long BACKOFF_MS = 5000;
	/** Extra chunks fetched around the visible area so small pans do not need a new query. */
	private static final int MARGIN_CHUNKS = 3;

	private static ChunkSnapshot snapshot;
	private static long snapshotMs;

	private static int wantedWhat;
	private static int wantedMinX;
	private static int wantedMinZ;
	private static int wantedMaxX;
	private static int wantedMaxZ;
	private static String wantedDimension;

	private static boolean pending;
	private static long sentMs;
	private static long nextSendMs;
	private static boolean denied;

	private ChunkDataClient() {
	}

	public static void init() {
		ClientPlayNetworking.registerGlobalReceiver(RTMapChannels.CHUNK_DATA, (client, handler, buf, sender) -> {
			if (buf.readableBytes() > RTMapChannels.MAX_CHUNK_DATA_BYTES) {
				RTMap.LOGGER.warn("Dropped oversized chunk_data");
				client.execute(() -> pending = false);
				return;
			}
			try {
				int status = buf.readUnsignedByte();
				ChunkSnapshot received = status == RTMapChannels.STATUS_CHUNK_OK
					? ChunkSnapshot.read(buf, RTMapChannels.MAX_QUERY_SIDE, RTMapChannels.MAX_QUERY_CHUNKS) : null;
				client.execute(() -> onResponse(status, received));
			} catch (RuntimeException e) {
				RTMap.LOGGER.warn("Malformed chunk_data", e);
				client.execute(() -> pending = false);
			}
		});

		ClientTickEvents.END_CLIENT_TICK.register(ChunkDataClient::tick);
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reset());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
	}

	private static void reset() {
		snapshot = null;
		wantedWhat = 0;
		pending = false;
		denied = false;
		nextSendMs = 0;
	}

	/** Whether chunk loading data can be had here: singleplayer, or a server that granted it. */
	public static boolean stateAvailable() {
		return available(RTMapChannels.FEATURE_CHUNK_STATE);
	}

	/** The heat map needs both the loading levels (to weigh a chunk) and the counts. */
	public static boolean loadAvailable() {
		return available(RTMapChannels.FEATURE_CHUNK_STATE) && available(RTMapChannels.FEATURE_CHUNK_LOAD);
	}

	private static boolean available(String feature) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.isIntegratedServerRunning()) {
			return true;
		}
		return ClientSession.grants().contains(feature) && ClientPlayNetworking.canSend(RTMapChannels.CHUNK_QUERY);
	}

	/** True when the server refused our last query, so layers can say why nothing is shown. */
	public static boolean denied() {
		return denied;
	}

	/** The newest data for the dimension being looked at, or null. */
	public static ChunkSnapshot snapshot() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (snapshot == null || client.world == null || !snapshot.dimension().equals(dimensionOf(client))) {
			return null;
		}
		return snapshot;
	}

	/** Called by a layer every frame it is drawn: this part of the map should have this kind of data. */
	public static void want(LayerView view, int what) {
		int firstX = (int) Math.floor(view.leftBlock() / 16.0);
		int lastX = (int) Math.floor(view.rightBlock() / 16.0);
		int firstZ = (int) Math.floor(view.topBlock() / 16.0);
		int lastZ = (int) Math.floor(view.bottomBlock() / 16.0);

		// A view wider than one query can hold is cut down around its centre.
		int centerX = (firstX + lastX) / 2;
		int centerZ = (firstZ + lastZ) / 2;
		int half = RTMapChannels.MAX_QUERY_SIDE / 2;
		firstX = Math.max(firstX, centerX - half + 1);
		lastX = Math.min(lastX, centerX + half - 1);
		firstZ = Math.max(firstZ, centerZ - half + 1);
		lastZ = Math.min(lastZ, centerZ + half - 1);

		wantedWhat |= what;
		wantedMinX = firstX;
		wantedMaxX = lastX;
		wantedMinZ = firstZ;
		wantedMaxZ = lastZ;
		wantedDimension = dimensionOf(MinecraftClient.getInstance());
	}

	private static String dimensionOf(MinecraftClient client) {
		return client.world == null ? "" : client.world.getRegistryKey().getValue().toString();
	}

	private static void tick(MinecraftClient client) {
		long now = Util.getMeasuringTimeMs();
		if (pending && now - sentMs > PENDING_TIMEOUT_MS) {
			pending = false;
		}

		int what = wantedWhat;
		wantedWhat = 0;
		if (what == 0 || pending || now < nextSendMs || client.world == null || client.player == null
			|| !wantedDimension.equals(dimensionOf(client))) {
			return;
		}

		boolean covered = snapshot != null && snapshot.dimension().equals(wantedDimension)
			&& (!wantsState(what) || snapshot.hasState()) && (!wantsLoad(what) || snapshot.hasLoad())
			&& snapshot.contains(wantedMinX, wantedMinZ) && snapshot.contains(wantedMaxX, wantedMaxZ)
			&& now - snapshotMs < REFRESH_MS;
		if (covered) {
			return;
		}

		// Fetch a little more than what is visible, but never more than one query may ask for.
		int width = wantedMaxX - wantedMinX + 1;
		int height = wantedMaxZ - wantedMinZ + 1;
		int marginX = Math.max(0, Math.min(MARGIN_CHUNKS, (RTMapChannels.MAX_QUERY_SIDE - width) / 2));
		int marginZ = Math.max(0, Math.min(MARGIN_CHUNKS, (RTMapChannels.MAX_QUERY_SIDE - height) / 2));
		send(client, what, wantedMinX - marginX, wantedMinZ - marginZ, width + 2 * marginX, height + 2 * marginZ, now);
	}

	private static boolean wantsState(int what) {
		return (what & RTMapChannels.QUERY_STATE) != 0;
	}

	private static boolean wantsLoad(int what) {
		return (what & RTMapChannels.QUERY_LOAD) != 0;
	}

	private static void send(MinecraftClient client, int what, int minX, int minZ, int width, int height, long now) {
		pending = true;
		sentMs = now;
		nextSendMs = now + MIN_INTERVAL_MS;

		if (client.isIntegratedServerRunning()) {
			MinecraftServer server = client.getServer();
			ServerWorld world = server == null ? null : server.getWorld(client.world.getRegistryKey());
			if (world == null) {
				pending = false;
				return;
			}
			server.submit(() -> ChunkDataCollector.collect(world, minX, minZ, width, height, what))
				.whenComplete((result, error) -> client.execute(() -> {
					if (result != null) {
						onResponse(RTMapChannels.STATUS_CHUNK_OK, result);
					} else {
						// Say so in the log, and wait a while so a broken read does not log every 0.6 seconds.
						RTMap.LOGGER.warn("Could not read chunk data from the integrated server", error);
						pending = false;
						nextSendMs = Util.getMeasuringTimeMs() + BACKOFF_MS;
					}
				}));
			return;
		}

		PacketByteBuf buf = PacketByteBufs.create();
		buf.writeString(wantedDimension, 128);
		buf.writeInt(minX);
		buf.writeInt(minZ);
		buf.writeVarInt(width);
		buf.writeVarInt(height);
		buf.writeByte(what);
		ClientPlayNetworking.send(RTMapChannels.CHUNK_QUERY, buf);
	}

	private static void onResponse(int status, ChunkSnapshot received) {
		pending = false;
		if (status == RTMapChannels.STATUS_CHUNK_OK && received != null) {
			denied = false;
			snapshot = received;
			snapshotMs = Util.getMeasuringTimeMs();
			return;
		}
		switch (status) {
			case RTMapChannels.STATUS_CHUNK_NOT_AUTHORIZED -> {
				denied = true;
				snapshot = null;
				nextSendMs = Util.getMeasuringTimeMs() + BACKOFF_MS;
			}
			case RTMapChannels.STATUS_CHUNK_RATE_LIMITED -> nextSendMs = Util.getMeasuringTimeMs() + BACKOFF_MS;
			default -> RTMap.LOGGER.debug("chunk_query not answered (status {})", status);
		}
	}
}
