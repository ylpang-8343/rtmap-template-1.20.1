package com.cabbage.rtmap.client.map;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

import com.cabbage.rtmap.RTMap;
import com.cabbage.rtmap.client.network.ClientSession;
import com.cabbage.rtmap.server.WorldIdState;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;

/**
 * Saves map tiles to <code>&lt;game dir&gt;/rtmap/maps/&lt;world&gt;/&lt;dimension&gt;/</code>: full-detail regions as
 * <code>r.&lt;x&gt;.&lt;z&gt;.rtm</code> and zoomed-out overview tiles as <code>l&lt;level&gt;.&lt;x&gt;.&lt;z&gt;.rtm</code>.
 *
 * <p>File format (version 1), all integers big-endian: the magic {@code RTMP}, a version byte, the region size
 * as an unsigned short, then a deflate stream of {@code size * size} little-endian ABGR pixels.
 *
 * <p>Writes run on one background thread. Until a write finishes, its data stays in {@link #PENDING}, so a
 * read never sees an older copy of a region that is still being saved.
 */
public final class MapStorage {
	private static final int MAGIC = 0x52544D50; // "RTMP"
	private static final int VERSION = 1;
	private static final int PIXEL_BYTES = MapRegion.SIZE * MapRegion.SIZE * 4;
	/** How long to wait for the server's world id before falling back to the server address. */
	private static final int WORLD_ID_WAIT_TICKS = 60;
	/** {@code r.x.z.rtm} is a full-detail region; {@code l<level>.x.z.rtm} is a zoomed-out overview tile. */
	private static final Pattern FILE_NAME = Pattern.compile("(?:r|l(\\d+))\\.(-?\\d+)\\.(-?\\d+)\\.rtm");

	private static final ExecutorService IO = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "rtmap-map-io");
		thread.setDaemon(true);
		return thread;
	});
	private static final Map<Path, int[]> PENDING = new ConcurrentHashMap<>();

	/** Directory of the current world; null until it has been identified. */
	private static Path worldDir;
	private static CompletableFuture<UUID> integratedWorldId;
	/** The server {@link #integratedWorldId} was requested from, so a result is never reused for another world. */
	private static IntegratedServer integratedServerAsked;
	private static int waitTicks;

	private MapStorage() {
	}

	public static boolean isResolved() {
		return worldDir != null;
	}

	/** Works out which world we are in. Returns true once the world is known. */
	public static boolean tick(MinecraftClient client) {
		if (worldDir != null) {
			return true;
		}

		IntegratedServer server = client.getServer();
		if (server != null) {
			// Read the id on the server thread, then pick it up here without blocking.
			if (integratedServerAsked != server) {
				integratedServerAsked = server;
				integratedWorldId = server.submit(() -> WorldIdState.get(server));
			}
			if (integratedWorldId.isDone()) {
				try {
					setWorld("world-" + integratedWorldId.getNow(null));
				} catch (RuntimeException e) {
					RTMap.LOGGER.warn("Could not read the world id of the integrated server", e);
					setWorld("world-unknown");
				}
			}
			return worldDir != null;
		}

		UUID id = ClientSession.worldId();
		if (id != null) {
			setWorld("world-" + id);
		} else if (++waitTicks >= WORLD_ID_WAIT_TICKS) {
			// The server does not have this mod (or is slow): tell worlds apart by address instead.
			ServerInfo info = client.getCurrentServerEntry();
			setWorld("addr-" + sanitize(info != null ? info.address : "unknown"));
		}
		return worldDir != null;
	}

	private static void setWorld(String name) {
		worldDir = FabricLoader.getInstance().getGameDir().resolve("rtmap").resolve("maps").resolve(sanitize(name));
		RTMap.LOGGER.info("Map storage: {}", worldDir);
	}

	/** Forgets the current world. Pending writes still finish. */
	public static void reset() {
		worldDir = null;
		integratedWorldId = null;
		integratedServerAsked = null;
		waitTicks = 0;
	}

	private static String sanitize(String text) {
		return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
	}

	private static Path dimensionDir(RegistryKey<World> dimension) {
		return worldDir.resolve(sanitize(dimension.getValue().toString()));
	}

	private static Path file(RegistryKey<World> dimension, int level, int x, int z) {
		String prefix = level == 0 ? "r" : "l" + level;
		return dimensionDir(dimension).resolve(prefix + "." + x + "." + z + ".rtm");
	}

	/**
	 * Which tiles of a dimension exist on disk, per level (0 is full detail), as {@link ChunkPos#toLong} keys.
	 * Every level from 0 to {@code maxLevel} has an entry, possibly empty.
	 */
	public static Map<Integer, Set<Long>> listTiles(RegistryKey<World> dimension, int maxLevel) {
		Map<Integer, Set<Long>> found = new HashMap<>();
		for (int level = 0; level <= maxLevel; level++) {
			found.put(level, new HashSet<>());
		}
		Path dir = dimensionDir(dimension);
		if (!Files.isDirectory(dir)) {
			return found;
		}
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.rtm")) {
			for (Path path : stream) {
				Matcher matcher = FILE_NAME.matcher(path.getFileName().toString());
				if (!matcher.matches()) {
					continue;
				}
				int level = matcher.group(1) == null ? 0 : Integer.parseInt(matcher.group(1));
				if (level <= maxLevel) {
					found.get(level).add(ChunkPos.toLong(Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3))));
				}
			}
		} catch (IOException | NumberFormatException e) {
			RTMap.LOGGER.warn("Could not list saved map tiles in {}", dir, e);
		}
		return found;
	}

	private static Path lodMarker(RegistryKey<World> dimension) {
		return dimensionDir(dimension).resolve("overview.ok");
	}

	/** Whether the overview tiles of this dimension are known to be complete. */
	public static boolean hasOverviewMarker(RegistryKey<World> dimension) {
		return Files.exists(lodMarker(dimension));
	}

	/** Records that the overview tiles are complete. Queued behind earlier writes, so it lands after them. */
	public static void writeOverviewMarker(RegistryKey<World> dimension) {
		Path path = lodMarker(dimension);
		IO.execute(() -> {
			try {
				Files.createDirectories(path.getParent());
				Files.writeString(path, "1");
			} catch (IOException e) {
				RTMap.LOGGER.warn("Could not write {}", path, e);
			}
		});
	}

	/** Reads a tile, or returns null if it does not exist or cannot be read. */
	public static int[] read(RegistryKey<World> dimension, int level, int x, int z) {
		Path path = file(dimension, level, x, z);
		int[] pending = PENDING.get(path);
		if (pending != null) {
			return pending;
		}
		if (!Files.exists(path)) {
			return null;
		}

		try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
			if (in.readInt() != MAGIC || in.readUnsignedByte() != VERSION || in.readUnsignedShort() != MapRegion.SIZE) {
				throw new IOException("unsupported file header");
			}
			try (InflaterInputStream inflater = new InflaterInputStream(in)) {
				byte[] raw = inflater.readNBytes(PIXEL_BYTES);
				if (raw.length != PIXEL_BYTES) {
					throw new IOException("file is truncated");
				}
				int[] pixels = new int[PIXEL_BYTES / 4];
				ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels);
				return pixels;
			}
		} catch (IOException e) {
			RTMap.LOGGER.warn("Could not read map region {}; setting it aside", path, e);
			quarantine(path);
			return null;
		}
	}

	/** Moves an unreadable file out of the way so it is never silently overwritten. */
	private static void quarantine(Path path) {
		try {
			Files.move(path, path.resolveSibling(path.getFileName() + ".bad"), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			RTMap.LOGGER.warn("Could not set aside {}", path, e);
		}
	}

	/** Queues a tile for writing on the background thread. {@code pixels} must not be modified afterwards. */
	public static void write(RegistryKey<World> dimension, int level, int x, int z, int[] pixels) {
		Path path = file(dimension, level, x, z);
		PENDING.put(path, pixels);
		IO.execute(() -> {
			try {
				writeNow(path, pixels);
			} catch (IOException e) {
				RTMap.LOGGER.error("Could not save map region {}", path, e);
			} finally {
				// Only drop our own entry; a newer snapshot of the same region may already be queued.
				PENDING.remove(path, pixels);
			}
		});
	}

	private static void writeNow(Path path, int[] pixels) throws IOException {
		Files.createDirectories(path.getParent());
		Path temp = path.resolveSibling(path.getFileName() + ".tmp");

		byte[] raw = new byte[PIXEL_BYTES];
		ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(pixels);

		Deflater deflater = new Deflater(Deflater.BEST_SPEED);
		try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
			out.writeInt(MAGIC);
			out.writeByte(VERSION);
			out.writeShort(MapRegion.SIZE);
			DeflaterOutputStream compressed = new DeflaterOutputStream(out, deflater);
			compressed.write(raw);
			compressed.finish();
		} finally {
			deflater.end();
		}

		// Replace the real file in one step so a crash never leaves a half-written region.
		try {
			Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** Waits for queued writes to finish; used when the game is closing. */
	public static void flushAndWait() {
		try {
			IO.submit(() -> { }).get(15, TimeUnit.SECONDS);
		} catch (Exception e) {
			RTMap.LOGGER.warn("Timed out waiting for map regions to be saved", e);
		}
	}
}
