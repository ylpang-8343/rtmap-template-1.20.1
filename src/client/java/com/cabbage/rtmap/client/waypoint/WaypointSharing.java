package com.cabbage.rtmap.client.waypoint;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Sharing waypoints through chat.
 *
 * <p>Sending only fills the chat box with a readable text, so the player sees and sends it themselves. Receiving
 * looks for that text in other players' messages and shows an "add / ignore" prompt; nothing is ever added without
 * a click. Everything in a received message is untrusted, so each part is length- and range-checked.
 */
public final class WaypointSharing {
	private static final Pattern PATTERN = Pattern.compile(
		"\\[rtmap: ([^;\\[\\]]{1,48}); (-?\\d{1,9}), (-?\\d{1,9}), (-?\\d{1,9}); ([a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,64})\\]");
	private static final int MAX_PENDING = 20;
	private static final int MAX_PER_MESSAGE = 3;
	private static final int COORDINATE_LIMIT = 30_000_000;
	private static final int Y_MIN = -2048;
	private static final int Y_MAX = 4096;

	private record Pending(UUID id, String name, int x, int y, int z, String dimension) {
	}

	private static final List<Pending> PENDING = new ArrayList<>();

	private WaypointSharing() {
	}

	/** The text for sharing a waypoint, like {@code [rtmap: Home; 12, 64, -30; minecraft:overworld]}. */
	public static String format(Waypoint waypoint) {
		String name = waypoint.name.replaceAll("[;\\[\\]]", " ").trim();
		if (name.isEmpty()) {
			name = "?";
		}
		if (name.length() > 48) {
			name = name.substring(0, 48);
		}
		return "[rtmap: " + name + "; " + waypoint.x + ", " + waypoint.y + ", " + waypoint.z + "; " + waypoint.dimension + "]";
	}

	public static void init() {
		ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, receptionTimestamp) ->
			handle(message.getString(), sender == null ? null : sender.getName()));
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay) {
				handle(message.getString(), null);
			}
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> PENDING.clear());

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
			ClientCommandManager.literal("rtmap_wp")
				.then(ClientCommandManager.literal("accept")
					.then(ClientCommandManager.argument("id", StringArgumentType.word()).executes(context -> resolve(context, true))))
				.then(ClientCommandManager.literal("ignore")
					.then(ClientCommandManager.argument("id", StringArgumentType.word()).executes(context -> resolve(context, false))))
		));
	}

	private static void handle(String text, String senderName) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || !WaypointStore.isLoaded()) {
			return;
		}
		// Our own message coming back to us is not a share from someone else.
		if (senderName != null && senderName.equals(client.player.getGameProfile().getName())) {
			return;
		}

		Matcher matcher = PATTERN.matcher(text);
		int found = 0;
		while (matcher.find() && found < MAX_PER_MESSAGE) {
			found++;
			Pending pending = parse(matcher);
			if (pending == null || isKnown(pending)) {
				continue;
			}
			PENDING.add(pending);
			while (PENDING.size() > MAX_PENDING) {
				PENDING.remove(0);
			}
			client.execute(() -> announce(client, pending, senderName));
		}
	}

	private static Pending parse(Matcher matcher) {
		try {
			int x = Integer.parseInt(matcher.group(2));
			int y = Integer.parseInt(matcher.group(3));
			int z = Integer.parseInt(matcher.group(4));
			if (Math.abs(x) > COORDINATE_LIMIT || Math.abs(z) > COORDINATE_LIMIT || y < Y_MIN || y > Y_MAX) {
				return null;
			}
			return new Pending(UUID.randomUUID(), matcher.group(1).trim(), x, y, z, matcher.group(5));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** Already saved, or already waiting for an answer. */
	private static boolean isKnown(Pending pending) {
		for (Waypoint waypoint : WaypointStore.waypoints()) {
			if (sameSpot(pending, waypoint.name, waypoint.x, waypoint.y, waypoint.z, waypoint.dimension)) {
				return true;
			}
		}
		for (Pending other : PENDING) {
			if (sameSpot(pending, other.name(), other.x(), other.y(), other.z(), other.dimension())) {
				return true;
			}
		}
		return false;
	}

	private static boolean sameSpot(Pending pending, String name, int x, int y, int z, String dimension) {
		return pending.name().equals(name) && pending.x() == x && pending.y() == y && pending.z() == z
			&& pending.dimension().equals(dimension);
	}

	private static void announce(MinecraftClient client, Pending pending, String senderName) {
		MutableText add = Text.translatable("waypoint.rtmap.add").styled(style -> style
			.withColor(Formatting.GREEN).withBold(true)
			.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/rtmap_wp accept " + pending.id()))
			.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.translatable("waypoint.rtmap.add_hover"))));
		MutableText ignore = Text.translatable("waypoint.rtmap.ignore").styled(style -> style
			.withColor(Formatting.RED)
			.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/rtmap_wp ignore " + pending.id())));

		MutableText line = Text.translatable("waypoint.rtmap.shared",
				senderName == null ? "?" : senderName, pending.name(), pending.x() + ", " + pending.y() + ", " + pending.z())
			.formatted(Formatting.GRAY)
			.append(" ").append(add).append(" ").append(ignore);
		client.inGameHud.getChatHud().addMessage(line);
	}

	private static int resolve(CommandContext<FabricClientCommandSource> context, boolean accept) {
		String idText = StringArgumentType.getString(context, "id");
		Pending pending = null;
		for (Pending candidate : PENDING) {
			if (candidate.id().toString().equals(idText)) {
				pending = candidate;
				break;
			}
		}
		if (pending == null) {
			context.getSource().sendError(Text.translatable("waypoint.rtmap.share_expired"));
			return 0;
		}
		PENDING.remove(pending);

		if (accept && WaypointStore.isLoaded()) {
			WaypointStore.add(Waypoint.create(pending.name(), pending.x(), pending.y(), pending.z(), pending.dimension(),
				WaypointGroup.DEFAULT_ID, Waypoints.randomColor()));
			context.getSource().sendFeedback(Text.translatable("waypoint.rtmap.share_added", pending.name()));
		}
		return 1;
	}
}
