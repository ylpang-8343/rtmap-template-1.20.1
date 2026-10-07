package com.cabbage.rtmap.client.network;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.cabbage.rtmap.RTMap;
import com.cabbage.rtmap.network.RTMapChannels;
import net.fabricmc.fabric.api.client.networking.v1.C2SPlayChannelEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;

/**
 * Client half of the RTMap protocol. The client always speaks first, and only to a server that has
 * registered our channels; anything else leaves the mod in client-only mode.
 */
public final class ClientNetworking {
	private static final List<String> CLIENT_FEATURES = List.of(RTMapChannels.FEATURE_SEED);

	private record ServerHello(int status, int protocol, UUID worldId, Set<String> features, Set<String> grants) {
	}

	private ClientNetworking() {
	}

	public static void init() {
		// Receivers run on the network thread: parse the buffer here, then hop to the client thread.
		ClientPlayNetworking.registerGlobalReceiver(RTMapChannels.SERVER_HELLO, (client, handler, buf, sender) -> {
			ServerHello hello = readServerHello(buf);
			if (hello != null) {
				client.execute(() -> onServerHello(client, hello));
			}
		});

		ClientPlayNetworking.registerGlobalReceiver(RTMapChannels.PERMISSIONS, (client, handler, buf, sender) -> {
			Set<String> grants = readGrants(buf);
			if (grants != null) {
				client.execute(() -> onPermissions(grants));
			}
		});

		ClientPlayNetworking.registerGlobalReceiver(RTMapChannels.SEED_RESPONSE, (client, handler, buf, sender) -> {
			if (buf.readableBytes() > RTMapChannels.MAX_PACKET_BYTES) {
				return;
			}
			try {
				int status = buf.readUnsignedByte();
				Long seed = status == RTMapChannels.STATUS_SEED_OK ? buf.readLong() : null;
				client.execute(() -> onSeedResponse(status, seed));
			} catch (RuntimeException e) {
				RTMap.LOGGER.warn("Malformed seed_response", e);
			}
		});

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> onJoin(client));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientSession.reset());

		// Fires when the server tells us which channels it listens on; only then do we know it has the mod.
		C2SPlayChannelEvents.REGISTER.register((handler, sender, client, channels) -> {
			if (channels.contains(RTMapChannels.CLIENT_HELLO)) {
				client.execute(() -> sendHello(client));
			}
		});
	}

	private static void onJoin(MinecraftClient client) {
		ClientSession.reset();
		if (client.isIntegratedServerRunning()) {
			// Singleplayer or our own LAN world: read the seed straight from the integrated server, no packets.
			ClientSession.setSeed(client.getServer().getOverworld().getSeed(), ClientSession.SeedSource.LOCAL);
		}
	}

	private static void sendHello(MinecraftClient client) {
		if (client.isIntegratedServerRunning() || ClientSession.helloSent()
			|| !ClientPlayNetworking.canSend(RTMapChannels.CLIENT_HELLO)) {
			return;
		}
		ClientSession.markHelloSent();

		PacketByteBuf buf = PacketByteBufs.create();
		buf.writeVarInt(RTMapChannels.PROTOCOL_VERSION);
		buf.writeVarInt(RTMapChannels.MIN_PROTOCOL_VERSION);
		buf.writeString(modVersion(), RTMapChannels.MAX_STRING_LENGTH);
		RTMapChannels.writeStringList(buf, CLIENT_FEATURES, CLIENT_FEATURES.size());
		ClientPlayNetworking.send(RTMapChannels.CLIENT_HELLO, buf);
	}

	private static String modVersion() {
		return FabricLoader.getInstance().getModContainer(RTMap.MOD_ID)
			.map(container -> container.getMetadata().getVersion().getFriendlyString())
			.orElse("unknown");
	}

	private static ServerHello readServerHello(PacketByteBuf buf) {
		if (buf.readableBytes() > RTMapChannels.MAX_PACKET_BYTES) {
			RTMap.LOGGER.warn("Dropped oversized server_hello");
			return null;
		}
		try {
			int status = buf.readUnsignedByte();
			int protocol = buf.readVarInt();
			UUID worldId = buf.readUuid();
			Set<String> features = new HashSet<>(RTMapChannels.readStringList(buf));
			Set<String> grants = new HashSet<>(RTMapChannels.readStringList(buf));
			return new ServerHello(status, protocol, worldId, features, grants);
		} catch (RuntimeException e) {
			RTMap.LOGGER.warn("Malformed server_hello", e);
			return null;
		}
	}

	private static Set<String> readGrants(PacketByteBuf buf) {
		if (buf.readableBytes() > RTMapChannels.MAX_PACKET_BYTES) {
			return null;
		}
		try {
			return new HashSet<>(RTMapChannels.readStringList(buf));
		} catch (RuntimeException e) {
			RTMap.LOGGER.warn("Malformed permissions packet", e);
			return null;
		}
	}

	private static void onServerHello(MinecraftClient client, ServerHello hello) {
		if (hello.status() != RTMapChannels.STATUS_HELLO_OK) {
			RTMap.LOGGER.warn("Server uses an incompatible RTMap protocol (server version {})", hello.protocol());
			client.inGameHud.getChatHud().addMessage(Text.literal(
				"[RTMap] This server's RTMap version is not compatible; server-assisted features are off."));
			return;
		}

		// Only trust grants for features we both agreed on.
		Set<String> grants = new HashSet<>(hello.grants());
		grants.retainAll(hello.features());
		ClientSession.setHandshake(hello.worldId(), grants);
		requestSeedIfGranted();
	}

	private static void onPermissions(Set<String> granted) {
		ClientSession.setGrants(granted);
		if (!granted.contains(RTMapChannels.FEATURE_SEED)) {
			ClientSession.clearServerSeed();
			return;
		}
		requestSeedIfGranted();
	}

	private static void requestSeedIfGranted() {
		if (!ClientSession.grants().contains(RTMapChannels.FEATURE_SEED)
			|| ClientSession.seed().isPresent()
			|| ClientSession.seedRequestPending()
			|| !ClientPlayNetworking.canSend(RTMapChannels.SEED_REQUEST)) {
			return;
		}
		ClientSession.markSeedRequestPending(true);
		ClientPlayNetworking.send(RTMapChannels.SEED_REQUEST, PacketByteBufs.create());
	}

	private static void onSeedResponse(int status, Long seed) {
		ClientSession.markSeedRequestPending(false);
		if (status == RTMapChannels.STATUS_SEED_OK && seed != null) {
			// Ignore a late answer if the grant was revoked while the request was in flight.
			if (ClientSession.grants().contains(RTMapChannels.FEATURE_SEED)) {
				ClientSession.setSeed(seed, ClientSession.SeedSource.SERVER);
			}
			return;
		}
		RTMap.LOGGER.debug("Server did not provide the seed (status {})", status);
		ClientSession.clearServerSeed();
	}
}
