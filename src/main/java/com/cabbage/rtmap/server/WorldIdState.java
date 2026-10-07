package com.cabbage.rtmap.server;

import java.util.UUID;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;

/** Persistent per-world identifier, saved with the overworld so copies of the save keep the same id. */
public final class WorldIdState extends PersistentState {
	private static final String KEY = "rtmap_world_id";

	private final UUID id;

	private WorldIdState(UUID id) {
		this.id = id;
	}

	private static WorldIdState create() {
		WorldIdState state = new WorldIdState(UUID.randomUUID());
		state.markDirty();
		return state;
	}

	private static WorldIdState fromNbt(NbtCompound nbt) {
		return nbt.containsUuid("id") ? new WorldIdState(nbt.getUuid("id")) : create();
	}

	@Override
	public NbtCompound writeNbt(NbtCompound nbt) {
		nbt.putUuid("id", id);
		return nbt;
	}

	public static UUID get(MinecraftServer server) {
		return server.getOverworld().getPersistentStateManager().getOrCreate(WorldIdState::fromNbt, WorldIdState::create, KEY).id;
	}
}
