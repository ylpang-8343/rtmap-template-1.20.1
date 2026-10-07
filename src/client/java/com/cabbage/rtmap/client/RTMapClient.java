package com.cabbage.rtmap.client;

import net.fabricmc.api.ClientModInitializer;

public class RTMapClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// Client-only setup (rendering, keybinds, screens) goes here.
		// Anything touching net.minecraft.client.* must live in the client source set.
	}
}
