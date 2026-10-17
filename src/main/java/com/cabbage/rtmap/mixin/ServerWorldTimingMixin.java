package com.cabbage.rtmap.mixin;

import com.cabbage.rtmap.chunk.ChunkTimingResult;
import com.cabbage.rtmap.server.ChunkProfiler;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.fluid.Fluid;
import net.minecraft.server.world.BlockEvent;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Times the parts of a server tick that belong to a place in the world. Each pair of injections only starts and stops
 * a clock; {@link ChunkProfiler} does nothing at all unless a profiling run is going.
 */
@Mixin(ServerWorld.class)
public abstract class ServerWorldTimingMixin {
	@Inject(method = "tickEntity(Lnet/minecraft/entity/Entity;)V", at = @At("HEAD"))
	private void rtmap$entityStart(Entity entity, CallbackInfo ci) {
		ChunkProfiler.begin();
	}

	@Inject(method = "tickEntity(Lnet/minecraft/entity/Entity;)V", at = @At("RETURN"))
	private void rtmap$entityEnd(Entity entity, CallbackInfo ci) {
		ChunkProfiler.end((ServerWorld) (Object) this, entity.getBlockX() >> 4, entity.getBlockZ() >> 4, ChunkTimingResult.ENTITY);
	}

	@Inject(method = "tickChunk", at = @At("HEAD"))
	private void rtmap$randomTickStart(WorldChunk chunk, int randomTickSpeed, CallbackInfo ci) {
		ChunkProfiler.begin();
	}

	@Inject(method = "tickChunk", at = @At("RETURN"))
	private void rtmap$randomTickEnd(WorldChunk chunk, int randomTickSpeed, CallbackInfo ci) {
		ChunkProfiler.end((ServerWorld) (Object) this, chunk.getPos().x, chunk.getPos().z, ChunkTimingResult.RANDOM_TICK);
	}

	@Inject(method = "tickBlock", at = @At("HEAD"))
	private void rtmap$blockTickStart(BlockPos pos, Block block, CallbackInfo ci) {
		ChunkProfiler.begin();
	}

	@Inject(method = "tickBlock", at = @At("RETURN"))
	private void rtmap$blockTickEnd(BlockPos pos, Block block, CallbackInfo ci) {
		ChunkProfiler.end((ServerWorld) (Object) this, pos.getX() >> 4, pos.getZ() >> 4, ChunkTimingResult.SCHEDULED_TICK);
	}

	@Inject(method = "tickFluid", at = @At("HEAD"))
	private void rtmap$fluidTickStart(BlockPos pos, Fluid fluid, CallbackInfo ci) {
		ChunkProfiler.begin();
	}

	@Inject(method = "tickFluid", at = @At("RETURN"))
	private void rtmap$fluidTickEnd(BlockPos pos, Fluid fluid, CallbackInfo ci) {
		ChunkProfiler.end((ServerWorld) (Object) this, pos.getX() >> 4, pos.getZ() >> 4, ChunkTimingResult.SCHEDULED_TICK);
	}

	@Inject(method = "processBlockEvent", at = @At("HEAD"))
	private void rtmap$blockEventStart(BlockEvent event, CallbackInfoReturnable<Boolean> cir) {
		ChunkProfiler.begin();
	}

	@Inject(method = "processBlockEvent", at = @At("RETURN"))
	private void rtmap$blockEventEnd(BlockEvent event, CallbackInfoReturnable<Boolean> cir) {
		BlockPos pos = event.pos();
		ChunkProfiler.end((ServerWorld) (Object) this, pos.getX() >> 4, pos.getZ() >> 4, ChunkTimingResult.BLOCK_EVENT);
	}
}
