package com.cabbage.rtmap.server;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.cabbage.rtmap.RTMap;
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

	private static final Set<String> SERVER_FEATURES = Set.of(RTMapChannels.FEATURE_SEED);
	private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

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

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			UUID id = handler.getPlayer().getUuid();
			SESSIONS.remove(id);
			SeedRateLimiter.forget(id);
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
		return grants;
	}

	private static void onSeedRequest(MinecraftServer server, ServerPlayerEntity player) {
		Session session = SESSIONS.get(player.getUuid());
		if (session == null) {
			return; // never handshaken; stay silent
		}

		ServerConfig config = ServerConfig.get();
		int status;
		if (!SeedRateLimiter.tryAcquire(player.getUuid(), config.seedRequestLimit(), config.seedRequestWindowSeconds())) {
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
