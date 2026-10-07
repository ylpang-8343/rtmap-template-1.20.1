package com.cabbage.rtmap.server;

import com.cabbage.rtmap.config.ServerConfig;
import net.minecraft.server.network.ServerPlayerEntity;

public final class SeedAccess {
	private static final int OP_LEVEL = 2;

	private SeedAccess() {
	}

	/** Ops always get the seed (as in singleplayer); everyone else needs the server-wide switch. */
	public static boolean isAllowed(ServerPlayerEntity player) {
		return player.hasPermissionLevel(OP_LEVEL) || ServerConfig.get().seedSharing();
	}
}
