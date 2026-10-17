package com.cabbage.rtmap.server;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.cabbage.rtmap.RTMap;
import com.cabbage.rtmap.chunk.ChunkTimingResult;
import com.cabbage.rtmap.config.ServerConfig;
import com.cabbage.rtmap.network.RTMapChannels;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

/** Server half of the RTMap protocol. Nothing is sent to a player who has not sent client_hello. */
public final class ServerNetworking {
	private record Session(int protocol, Set<String> features) {
	}

	private record ClientHello(int protocol, int minProtocol, List<String> features) {
		static ClientHello read(PacketByteBuf buf, ServerPlayerEntity player) {
			if (buf.readableBytes() > RTMapChannels.MAX_PACKET_BYTES) {
				RTMap.LOGGER.warn("Dropped oversized client_hello from {}", player.getGameProfile().getName());
				return null;
			}
			try {
				int protocol = buf.readVarInt();
				int minProtocol = buf.readVarInt();
				buf.readString(RTMapChannels.MAX_STRING_LENGTH); // mod_version, logging only
				List<String> features = RTMapChannels.readStringList(buf);
				return new ClientHello(protocol, minProtocol, features);
			} catch (RuntimeException e) {
				RTMap.LOGGER.warn("Malformed client_hello from {}", player.getGameProfile().getName(), e);
				return null;
			}
		}
	}

	/** What a chunk_query asks for; parsed on the network thread, answered on the server thread. */
	private record ChunkQuery(String dimension, int minChunkX, int minChunkZ, int width, int height, int what) {
		static ChunkQuery read(PacketByteBuf buf, ServerPlayerEntity player) {
			if (buf.readableBytes() > RTMapChannels.MAX_PACKET_BYTES) {
				RTMap.LOGGER.warn("Dropped oversized chunk_query from {}", player.getGameProfile().getName());
				return null;
			}
			try {
				return new ChunkQuery(buf.readString(128), buf.readInt(), buf.readInt(), buf.readVarInt(), buf.readVarInt(),
					buf.readUnsignedByte());
			} catch (RuntimeException e) {
				RTMap.LOGGER.warn("Malformed chunk_query from {}", player.getGameProfile().getName(), e);
				return null;
			}
		}
	}

	private static final Set<String> SERVER_FEATURES = Set.of(
		RTMapChannels.FEATURE_SEED, RTMapChannels.FEATURE_CHUNK_STATE, RTMapChannels.FEATURE_CHUNK_LOAD, RTMapChannels.FEATURE_MSPT,
		RTMapChannels.FEATURE_CHUNK_TIMING);
	private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

	private static final RateLimiter SEED_LIMITER = new RateLimiter();
	private static final RateLimiter CHUNK_LIMITER = new RateLimiter();
	private static final RateLimiter MSPT_LIMITER = new RateLimiter();
	private static final RateLimiter PROFILE_LIMITER = new RateLimiter();
	/** Starting a run is rare, so this is much stricter than the query limit. */
	private static final int PROFILE_START_LIMIT = 6;
	private static final int PROFILE_START_WINDOW_SECONDS = 60;
	/** A map that refreshes every second or two stays well under this; a flood of queries does not. */
	private static final int CHUNK_QUERY_LIMIT = 10;
	private static final int CHUNK_QUERY_WINDOW_SECONDS = 5;
	/** Chunk coordinates beyond the world border limit (30 million blocks) are never valid. */
	private static final int MAX_CHUNK_COORDINATE = 1_900_000;

	private ServerNetworking() {
	}

	public static void init() {
		// Receivers run on the network thread: parse the buffer here, then hop to the server thread.
		ServerPlayNetworking.registerGlobalReceiver(RTMapChannels.CLIENT_HELLO, (server, player, handler, buf, sender) -> {
			ClientHello hello = ClientHello.read(buf, player);
			if (hello != null) {
				server.execute(() -> onClientHello(server, player, hello));
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(RTMapChannels.SEED_REQUEST, (server, player, handler, buf, sender) ->
			server.execute(() -> onSeedRequest(server, player)));

		ServerPlayNetworking.registerGlobalReceiver(RTMapChannels.CHUNK_QUERY, (server, player, handler, buf, sender) -> {
			ChunkQuery query = ChunkQuery.read(buf, player);
			if (query != null) {
				server.execute(() -> onChunkQuery(player, query));
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(RTMapChannels.MSPT_QUERY, (server, player, handler, buf, sender) ->
			server.execute(() -> onMsptQuery(server, player)));

		ServerPlayNetworking.registerGlobalReceiver(RTMapChannels.PROFILE_CONTROL, (server, player, handler, buf, sender) -> {
			if (buf.readableBytes() > 64) {
				return;
			}
			try {
				int action = buf.readUnsignedByte();
				int seconds = buf.readVarInt();
				server.execute(() -> onProfileControl(server, player, action, seconds));
			} catch (RuntimeException e) {
				RTMap.LOGGER.warn("Malformed profile_control from {}", player.getGameProfile().getName(), e);
			}
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			UUID id = handler.getPlayer().getUuid();
			SESSIONS.remove(id);
			SEED_LIMITER.forget(id);
			CHUNK_LIMITER.forget(id);
			MSPT_LIMITER.forget(id);
			PROFILE_LIMITER.forget(id);
		});
	}

	private static void onClientHello(MinecraftServer server, ServerPlayerEntity player, ClientHello hello) {
		int lowest = Math.max(hello.minProtocol(), RTMapChannels.MIN_PROTOCOL_VERSION);
		int negotiated = Math.min(hello.protocol(), RTMapChannels.PROTOCOL_VERSION);

		PacketByteBuf out = PacketByteBufs.create();
		if (negotiated < lowest) {
			SESSIONS.remove(player.getUuid());
			out.writeByte(RTMapChannels.STATUS_HELLO_INCOMPATIBLE);
			out.writeVarInt(RTMapChannels.PROTOCOL_VERSION);
			out.writeUuid(new UUID(0L, 0L));
			RTMapChannels.writeStringList(out, Set.of(), 0);
			RTMapChannels.writeStringList(out, Set.of(), 0);
			ServerPlayNetworking.send(player, RTMapChannels.SERVER_HELLO, out);
			RTMap.LOGGER.info("{} has an incompatible RTMap protocol ({}..{})",
				player.getGameProfile().getName(), hello.minProtocol(), hello.protocol());
			return;
		}

		Set<String> features = new LinkedHashSet<>();
		for (String feature : hello.features()) {
			if (SERVER_FEATURES.contains(feature)) {
				features.add(feature);
			}
		}
		SESSIONS.put(player.getUuid(), new Session(negotiated, features));

		Set<String> grants = grantsFor(player, features);
		out.writeByte(RTMapChannels.STATUS_HELLO_OK);
		out.writeVarInt(negotiated);
		out.writeUuid(WorldIdState.get(server));
		RTMapChannels.writeStringList(out, features, features.size());
		RTMapChannels.writeStringList(out, grants, grants.size());
		ServerPlayNetworking.send(player, RTMapChannels.SERVER_HELLO, out);
		RTMap.LOGGER.info("RTMap handshake with {} (protocol {})", player.getGameProfile().getName(), negotiated);
	}

	private static Set<String> grantsFor(ServerPlayerEntity player, Set<String> features) {
		Set<String> grants = new LinkedHashSet<>();
		if (features.contains(RTMapChannels.FEATURE_SEED) && SeedAccess.isAllowed(player)) {
			grants.add(RTMapChannels.FEATURE_SEED);
		}
		if (ChunkDataAccess.isAllowed(player)) {
			if (features.contains(RTMapChannels.FEATURE_CHUNK_STATE)) {
				grants.add(RTMapChannels.FEATURE_CHUNK_STATE);
			}
			if (features.contains(RTMapChannels.FEATURE_CHUNK_LOAD)) {
				grants.add(RTMapChannels.FEATURE_CHUNK_LOAD);
			}
			if (features.contains(RTMapChannels.FEATURE_MSPT)) {
				grants.add(RTMapChannels.FEATURE_MSPT);
			}
		}
		if (features.contains(RTMapChannels.FEATURE_CHUNK_TIMING) && ChunkDataAccess.canProfile(player)) {
			grants.add(RTMapChannels.FEATURE_CHUNK_TIMING);
		}
		return grants;
	}

	private static void onChunkQuery(ServerPlayerEntity player, ChunkQuery query) {
		Session session = SESSIONS.get(player.getUuid());
		if (session == null) {
			return; // never handshaken; stay silent
		}

		int status = chunkQueryStatus(player, session, query);
		PacketByteBuf out = PacketByteBufs.create();
		out.writeByte(status);
		if (status == RTMapChannels.STATUS_CHUNK_OK) {
			ChunkDataCollector.collect(player.getServerWorld(), query.minChunkX(), query.minChunkZ(),
				query.width(), query.height(), query.what()).write(out);
		}
		ServerPlayNetworking.send(player, RTMapChannels.CHUNK_DATA, out);
	}

	private static void onProfileControl(MinecraftServer server, ServerPlayerEntity player, int action, int seconds) {
		Session session = SESSIONS.get(player.getUuid());
		if (session == null) {
			return; // never handshaken; stay silent
		}
		if (action == RTMapChannels.PROFILE_ACTION_STOP) {
			ChunkProfiler.stop(player.getUuid()); // only the player who started a run can stop it
			return;
		}

		int reason = 0;
		if (action != RTMapChannels.PROFILE_ACTION_START || seconds < ChunkProfiler.MIN_SECONDS || seconds > ChunkProfiler.MAX_SECONDS) {
			reason = RTMapChannels.PROFILE_REASON_BAD_REQUEST;
		} else if (!session.features().contains(RTMapChannels.FEATURE_CHUNK_TIMING) || !player.hasPermissionLevel(2)) {
			reason = RTMapChannels.PROFILE_REASON_NOT_AUTHORIZED;
		} else if (!ServerConfig.get().chunkProfiling()) {
			reason = RTMapChannels.PROFILE_REASON_DISABLED;
		} else if (!PROFILE_LIMITER.tryAcquire(player.getUuid(), PROFILE_START_LIMIT, PROFILE_START_WINDOW_SECONDS)) {
			reason = RTMapChannels.PROFILE_REASON_RATE_LIMITED;
		} else {
			UUID id = player.getUuid();
			if (ChunkProfiler.start(id, seconds, result -> deliverProfileResult(server, id, result)) == ChunkProfiler.Outcome.BUSY) {
				reason = RTMapChannels.PROFILE_REASON_BUSY;
			}
		}

		if (reason == 0) {
			RTMap.LOGGER.info("{} started a {} second chunk timing run", player.getGameProfile().getName(), seconds);
		}
		sendProfileState(player, reason == 0 ? RTMapChannels.PROFILE_STATE_RUNNING : RTMapChannels.PROFILE_STATE_DENIED, reason, seconds);
	}

	/** Called on the server thread when a run ends. The player may have left in the meantime. */
	private static void deliverProfileResult(MinecraftServer server, UUID id, ChunkTimingResult result) {
		ServerPlayerEntity player = server.getPlayerManager().getPlayer(id);
		if (player == null || !SESSIONS.containsKey(id)) {
			return;
		}
		PacketByteBuf out = PacketByteBufs.create();
		result.write(out);
		ServerPlayNetworking.send(player, RTMapChannels.PROFILE_RESULT, out);
		sendProfileState(player, RTMapChannels.PROFILE_STATE_IDLE, 0, 0);
	}

	private static void sendProfileState(ServerPlayerEntity player, int state, int reason, int seconds) {
		PacketByteBuf out = PacketByteBufs.create();
		out.writeByte(state);
		out.writeByte(reason);
		out.writeVarInt(seconds);
		ServerPlayNetworking.send(player, RTMapChannels.PROFILE_STATE, out);
	}

	private static void onMsptQuery(MinecraftServer server, ServerPlayerEntity player) {
		Session session = SESSIONS.get(player.getUuid());
		if (session == null) {
			return; // never handshaken; stay silent
		}

		int status;
		if (!MSPT_LIMITER.tryAcquire(player.getUuid(), CHUNK_QUERY_LIMIT, CHUNK_QUERY_WINDOW_SECONDS)) {
			status = RTMapChannels.STATUS_MSPT_RATE_LIMITED;
		} else if (!ChunkDataAccess.isAllowed(player) || !session.features().contains(RTMapChannels.FEATURE_MSPT)) {
			status = RTMapChannels.STATUS_MSPT_NOT_AUTHORIZED;
		} else {
			status = RTMapChannels.STATUS_MSPT_OK;
		}

		PacketByteBuf out = PacketByteBufs.create();
		out.writeByte(status);
		if (status == RTMapChannels.STATUS_MSPT_OK) {
			float[] millis = ServerPerformance.recentTickMillis(server);
			out.writeFloat(millis[0]);
			out.writeFloat(millis[1]);
		}
		ServerPlayNetworking.send(player, RTMapChannels.MSPT_DATA, out);
	}

	private static int chunkQueryStatus(ServerPlayerEntity player, Session session, ChunkQuery query) {
		if (!CHUNK_LIMITER.tryAcquire(player.getUuid(), CHUNK_QUERY_LIMIT, CHUNK_QUERY_WINDOW_SECONDS)) {
			return RTMapChannels.STATUS_CHUNK_RATE_LIMITED;
		}
		int what = query.what();
		boolean wantState = (what & RTMapChannels.QUERY_STATE) != 0;
		boolean wantLoad = (what & RTMapChannels.QUERY_LOAD) != 0;
		if ((what & ~(RTMapChannels.QUERY_STATE | RTMapChannels.QUERY_LOAD)) != 0 || (!wantState && !wantLoad)
			|| query.width() < 1 || query.height() < 1
			|| query.width() > RTMapChannels.MAX_QUERY_SIDE || query.height() > RTMapChannels.MAX_QUERY_SIDE
			|| (long) query.width() * query.height() > RTMapChannels.MAX_QUERY_CHUNKS
			|| Math.abs(query.minChunkX()) > MAX_CHUNK_COORDINATE || Math.abs(query.minChunkZ()) > MAX_CHUNK_COORDINATE) {
			return RTMapChannels.STATUS_CHUNK_BAD_REQUEST;
		}
		if (!ChunkDataAccess.isAllowed(player)
			|| (wantState && !session.features().contains(RTMapChannels.FEATURE_CHUNK_STATE))
			|| (wantLoad && !session.features().contains(RTMapChannels.FEATURE_CHUNK_LOAD))) {
			return RTMapChannels.STATUS_CHUNK_NOT_AUTHORIZED;
		}
		// The client may have asked just before changing dimension; answering for the wrong one would mislead it.
		if (!player.getServerWorld().getRegistryKey().getValue().toString().equals(query.dimension())) {
			return RTMapChannels.STATUS_CHUNK_WRONG_DIMENSION;
		}
		return RTMapChannels.STATUS_CHUNK_OK;
	}

	private static void onSeedRequest(MinecraftServer server, ServerPlayerEntity player) {
		Session session = SESSIONS.get(player.getUuid());
		if (session == null) {
			return; // never handshaken; stay silent
		}

		ServerConfig config = ServerConfig.get();
		int status;
		if (!SEED_LIMITER.tryAcquire(player.getUuid(), config.seedRequestLimit(), config.seedRequestWindowSeconds())) {
			status = RTMapChannels.STATUS_SEED_RATE_LIMITED;
		} else if (!session.features().contains(RTMapChannels.FEATURE_SEED)) {
			status = RTMapChannels.STATUS_SEED_FEATURE_DISABLED;
		} else if (!SeedAccess.isAllowed(player)) {
			status = RTMapChannels.STATUS_SEED_NOT_AUTHORIZED;
		} else {
			status = RTMapChannels.STATUS_SEED_OK;
		}

		PacketByteBuf out = PacketByteBufs.create();
		out.writeByte(status);
		if (status == RTMapChannels.STATUS_SEED_OK) {
			out.writeLong(server.getOverworld().getSeed());
		}
		ServerPlayNetworking.send(player, RTMapChannels.SEED_RESPONSE, out);
		RTMap.LOGGER.debug("Seed request from {}: status {}", player.getGameProfile().getName(), status);
	}

	/** Tells one handshaken player what they are currently granted. */
	public static void sendPermissions(ServerPlayerEntity player) {
		Session session = SESSIONS.get(player.getUuid());
		if (session == null) {
			return;
		}
		Set<String> grants = grantsFor(player, session.features());
		PacketByteBuf out = PacketByteBufs.create();
		RTMapChannels.writeStringList(out, grants, grants.size());
		ServerPlayNetworking.send(player, RTMapChannels.PERMISSIONS, out);
	}

	public static void sendPermissionsToAll(MinecraftServer server) {
		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			sendPermissions(player);
		}
	}
}
