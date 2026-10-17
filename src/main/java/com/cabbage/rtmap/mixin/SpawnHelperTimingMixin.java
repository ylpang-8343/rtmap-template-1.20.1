package com.cabbage.rtmap.mixin;

import com.cabbage.rtmap.chunk.ChunkTimingResult;
import com.cabbage.rtmap.server.ChunkProfiler;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.SpawnHelper;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Times the mob spawning attempt of each chunk. */
@Mixin(SpawnHelper.class)
public abstract class SpawnHelperTimingMixin {
	@Inject(method = "spawn(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/world/chunk/WorldChunk;Lnet/minecraft/world/SpawnHelper$Info;ZZZ)V",
		at = @At("HEAD"))
	private static void rtmap$spawnStart(ServerWorld world, WorldChunk chunk, SpawnHelper.Info info, boolean spawnAnimals,
		boolean spawnMonsters, boolean rareSpawn, CallbackInfo ci) {
		ChunkProfiler.begin();
	}

	@Inject(method = "spawn(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/world/chunk/WorldChunk;Lnet/minecraft/world/SpawnHelper$Info;ZZZ)V",
		at = @At("RETURN"))
	private static void rtmap$spawnEnd(ServerWorld world, WorldChunk chunk, SpawnHelper.Info info, boolean spawnAnimals,
		boolean spawnMonsters, boolean rareSpawn, CallbackInfo ci) {
		ChunkProfiler.end(world, chunk.getPos().x, chunk.getPos().z, ChunkTimingResult.SPAWNING);
	}
}
