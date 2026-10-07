package com.cabbage.rtmap.mixin;

import com.cabbage.rtmap.server.ServerNetworking;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla fires no event when someone is opped or deopped, so hook the op list directly. */
@Mixin(PlayerManager.class)
public abstract class PlayerManagerMixin {
	@Inject(method = "addToOperators", at = @At("TAIL"))
	private void rtmap$onOpped(GameProfile profile, CallbackInfo ci) {
		rtmap$refresh(profile);
	}

	@Inject(method = "removeFromOperators", at = @At("TAIL"))
	private void rtmap$onDeopped(GameProfile profile, CallbackInfo ci) {
		rtmap$refresh(profile);
	}

	private void rtmap$refresh(GameProfile profile) {
		ServerPlayerEntity player = ((PlayerManager) (Object) this).getPlayer(profile.getId());
		if (player != null) {
			ServerNetworking.sendPermissions(player);
		}
	}
}
