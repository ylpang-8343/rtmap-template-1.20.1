package com.cabbage.rtmap.mixin;

import com.cabbage.rtmap.chunk.ChunkTimingResult;
import com.cabbage.rtmap.server.ChunkProfiler;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Times the ticking of each block entity (hoppers, furnaces, command blocks, pistons in motion). The wrapper that
 * a chunk puts around its tickers calls this class in the end, so this one place covers them all.
 */
@Mixin(targets = "net.minecraft.world.chunk.WorldChunk$DirectBlockEntityTickInvoker")
public abstract class BlockEntityTickInvokerTimingMixin {
	@Shadow
	@Final
	private BlockEntity blockEntity;

	@Inject(method = "tick", at = @At("HEAD"))
	private void rtmap$blockEntityStart(CallbackInfo ci) {
		ChunkProfiler.begin();
	}

	@Inject(method = "tick", at = @At("RETURN"))
	private void rtmap$blockEntityEnd(CallbackInfo ci) {
		BlockPos pos = blockEntity.getPos();
		ChunkProfiler.end(blockEntity.getWorld(), pos.getX() >> 4, pos.getZ() >> 4, ChunkTimingResult.BLOCK_ENTITY);
	}
}
