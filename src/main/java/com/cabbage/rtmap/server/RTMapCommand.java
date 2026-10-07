package com.cabbage.rtmap.server;

import com.cabbage.rtmap.config.ServerConfig;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

public final class RTMapCommand {
	private RTMapCommand() {
	}

	public static void init() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
			CommandManager.literal("rtmap")
				// Read on every check so a reloaded level takes effect; the config clamps it to at least 2.
				.requires(source -> source.hasPermissionLevel(ServerConfig.get().commandPermissionLevel()))
				.then(CommandManager.literal("seed_sharing")
					.executes(RTMapCommand::querySeedSharing)
					.then(CommandManager.argument("enabled", BoolArgumentType.bool())
						.executes(RTMapCommand::setSeedSharing)))
				.then(CommandManager.literal("reload")
					.executes(RTMapCommand::reload))
		));
	}

	private static int querySeedSharing(CommandContext<ServerCommandSource> context) {
		boolean enabled = ServerConfig.get().seedSharing();
		context.getSource().sendFeedback(() -> Text.literal("seed_sharing is " + enabled), false);
		return enabled ? 1 : 0;
	}

	private static int setSeedSharing(CommandContext<ServerCommandSource> context) {
		boolean enabled = BoolArgumentType.getBool(context, "enabled");
		boolean saved = ServerConfig.update(ServerConfig.get().withSeedSharing(enabled));
		ServerNetworking.sendPermissionsToAll(context.getSource().getServer());

		context.getSource().sendFeedback(() -> Text.literal("seed_sharing set to " + enabled), true);
		if (!saved) {
			context.getSource().sendError(Text.literal("Applied, but could not save rtmap-server.json; see the server log"));
		}
		return 1;
	}

	private static int reload(CommandContext<ServerCommandSource> context) {
		if (!ServerConfig.load()) {
			context.getSource().sendError(Text.literal("Could not read rtmap-server.json; keeping the previous settings"));
			return 0;
		}
		MinecraftServer server = context.getSource().getServer();
		ServerNetworking.sendPermissionsToAll(server);
		// The command level may have changed, so refresh what clients see in tab completion.
		server.getPlayerManager().getPlayerList().forEach(server.getPlayerManager()::sendCommandTree);

		context.getSource().sendFeedback(() -> Text.literal("Reloaded rtmap-server.json"), true);
		return 1;
	}
}
