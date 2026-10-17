package com.cabbage.rtmap.server;

import com.cabbage.rtmap.config.ServerConfig;
import net.minecraft.server.network.ServerPlayerEntity;

public final class ChunkDataAccess {
	private static final int OP_LEVEL = 2;

	private ChunkDataAccess() {
	}

	/** Starting a profiling run is for ops only, whatever the sharing switch says, and the server can switch it off. */
	public static boolean canProfile(ServerPlayerEntity player) {
		return player.hasPermissionLevel(OP_LEVEL) && ServerConfig.get().chunkProfiling();
	}

	/** Ops always see chunk loading and load data; everyone else needs the server-wide switch. */
	public static boolean isAllowed(ServerPlayerEntity player) {
		return player.hasPermissionLevel(OP_LEVEL) || ServerConfig.get().chunkDataSharing();
	}
}
