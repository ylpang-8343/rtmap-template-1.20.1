package com.cabbage.rtmap.client.waypoint;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import com.cabbage.rtmap.client.config.ClientConfig;
import com.cabbage.rtmap.client.map.MapStorage;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/** Key bindings, death waypoints and teleporting. The waypoint data itself lives in {@link WaypointStore}. */
public final class Waypoints {
	/** The colours offered in the editor, as RGB. */
	public static final int[] PALETTE = {
		0xE53935, 0xF4511E, 0xFFB300, 0xFDD835, 0xC0CA33, 0x43A047, 0x00897B, 0x00ACC1,
		0x1E88E5, 0x3949AB, 0x8E24AA, 0xD81B60, 0x6D4C41, 0x757575, 0xEEEEEE, 0x212121,
	};
	private static final int DEATH_COLOR = 0x757575;

	private static final Random RANDOM = new Random();

	private static KeyBinding createKey;
	private static KeyBinding managerKey;
	private static boolean wasDead;

	private Waypoints() {
	}

	public static KeyBinding createKey() {
		return createKey;
	}

	public static KeyBinding managerKey() {
		return managerKey;
	}

	public static void init() {
		// B and N are free in vanilla; they are also what JourneyMap uses for "new waypoint" and the manager.
		createKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.rtmap.create_waypoint", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_B, "key.categories.rtmap"));
		managerKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.rtmap.waypoint_manager", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_N, "key.categories.rtmap"));

		ClientTickEvents.END_CLIENT_TICK.register(Waypoints::tick);
		WorldWaypointRenderer.init();
		WaypointSharing.init();
	}

	public static int randomColor() {
		return PALETTE[RANDOM.nextInt(PALETTE.length - 1)]; // the last one is near black; not a good default
	}

	/** A readable name for a dimension id; unknown dimensions show their id. */
	public static Text dimensionName(String id) {
		return switch (id) {
			case "minecraft:overworld" -> Text.translatable("waypoint.rtmap.dimension.overworld");
			case "minecraft:the_nether" -> Text.translatable("waypoint.rtmap.dimension.nether");
			case "minecraft:the_end" -> Text.translatable("waypoint.rtmap.dimension.end");
			default -> Text.literal(id);
		};
	}

	public static String dimensionId(ClientWorld world) {
		return world.getRegistryKey().getValue().toString();
	}

	private static void tick(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		ClientWorld world = client.world;
		if (player == null || world == null || !WaypointStore.isLoaded()) {
			wasDead = false;
			return;
		}

		while (createKey.wasPressed()) {
			if (client.currentScreen == null) {
				client.setScreen(new WaypointEditorScreen(null, null, player.getBlockX(), player.getBlockY(), player.getBlockZ(),
					dimensionId(world)));
			}
		}
		while (managerKey.wasPressed()) {
			if (client.currentScreen == null) {
				client.setScreen(new WaypointManagerScreen(null));
			}
		}

		boolean dead = player.isDead();
		if (dead && !wasDead && ClientConfig.createDeathWaypoints()) {
			addDeathWaypoint(player, world);
		}
		wasDead = dead;

		if (!dead) {
			removeVisitedDeathWaypoints(player, world);
		}
	}

	private static void addDeathWaypoint(ClientPlayerEntity player, ClientWorld world) {
		String when = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date());
		Waypoint waypoint = new Waypoint(java.util.UUID.randomUUID(), Waypoint.Kind.DEATH, System.currentTimeMillis(),
			Text.translatable("waypoint.rtmap.death_name", when).getString(),
			player.getBlockX(), player.getBlockY(), player.getBlockZ(), dimensionId(world),
			WaypointGroup.DEATH_ID, DEATH_COLOR);
		WaypointStore.add(waypoint);
	}

	/** A death waypoint is a reminder to fetch your items; once you are back there it has done its job. */
	private static void removeVisitedDeathWaypoints(ClientPlayerEntity player, ClientWorld world) {
		String dimension = dimensionId(world);
		double range = ClientConfig.deathWaypointRemoveDistance();
		List<Waypoint> done = null;
		for (Waypoint waypoint : WaypointStore.waypoints()) {
			if (waypoint.kind != Waypoint.Kind.DEATH || !waypoint.dimension.equals(dimension)) {
				continue;
			}
			double dx = player.getX() - (waypoint.x + 0.5);
			double dy = player.getY() - waypoint.y;
			double dz = player.getZ() - (waypoint.z + 0.5);
			double distanceSquared = dx * dx + dy * dy + dz * dz;
			if (distanceSquared > (range + 16) * (range + 16)) {
				waypoint.armed = true;
			} else if (waypoint.armed && distanceSquared <= range * range) {
				if (done == null) {
					done = new ArrayList<>();
				}
				done.add(waypoint);
			}
		}
		if (done != null) {
			done.forEach(WaypointStore::remove);
		}
	}

	/** Asks the server to teleport the player. The server decides whether that is allowed. */
	public static void teleport(Waypoint waypoint) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) {
			return;
		}
		String command = ClientConfig.teleportCommand()
			.replace("{dim}", waypoint.dimension)
			.replace("{x}", Integer.toString(waypoint.x))
			.replace("{y}", Integer.toString(waypoint.y))
			.replace("{z}", Integer.toString(waypoint.z))
			.replace("{cx}", Double.toString(waypoint.x + 0.5))
			.replace("{cz}", Double.toString(waypoint.z + 0.5))
			.replace("{name}", client.player.getGameProfile().getName());
		client.player.networkHandler.sendChatCommand(command);
	}

	/** A waypoint's block position as text, for lists. */
	public static String coordinates(Waypoint waypoint) {
		return waypoint.x + ", " + waypoint.y + ", " + waypoint.z;
	}

	/** Whether the world storage is ready, so a waypoint can be saved. */
	public static boolean ready() {
		return WaypointStore.isLoaded() && MapStorage.isResolved();
	}
}
