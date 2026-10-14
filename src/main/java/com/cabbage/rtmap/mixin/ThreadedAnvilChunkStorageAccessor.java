package com.cabbage.rtmap.mixin;

import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Read access to the chunk holders, which vanilla only exposes to its own package. */
@Mixin(ThreadedAnvilChunkStorage.class)
public interface ThreadedAnvilChunkStorageAccessor {
	/** The holder for a chunk (packed with {@code ChunkPos.toLong}), or null if none exists. */
	@Invoker("getChunkHolder")
	ChunkHolder rtmap$getChunkHolder(long pos);
}
