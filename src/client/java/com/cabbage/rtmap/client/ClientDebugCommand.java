package com.cabbage.rtmap.client;

import java.util.TreeSet;
import java.util.UUID;

import com.cabbage.rtmap.client.network.ClientSession;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;

/** Client-side /rtmap_seed: prints what this client currently knows, for testing the protocol. */
public final class ClientDebugCommand {
	private ClientDebugCommand() {
	}

	public static void init() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
			ClientCommandManager.literal("rtmap_seed").executes(ClientDebugCommand::print)
		));
	}

	private static int print(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		var seed = ClientSession.seed();
		var seedSource = ClientSession.seedSource();
		UUID worldId = ClientSession.worldId();

		source.sendFeedback(Text.literal("[RTMap] seed: " + (seed.isPresent() ? seed.getAsLong() : "none")));
		source.sendFeedback(Text.literal("[RTMap] seed source: " + (seedSource == null ? "none" : seedSource)));
		source.sendFeedback(Text.literal("[RTMap] grants: " + new TreeSet<>(ClientSession.grants())));
		source.sendFeedback(Text.literal("[RTMap] world id: " + (worldId == null ? "none" : worldId)));
		return seed.isPresent() ? 1 : 0;
	}
}
