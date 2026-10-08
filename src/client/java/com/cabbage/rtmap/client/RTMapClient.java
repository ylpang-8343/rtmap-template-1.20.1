package com.cabbage.rtmap.client;

import com.cabbage.rtmap.client.map.ClientMap;
import com.cabbage.rtmap.client.network.ClientNetworking;
import net.fabricmc.api.ClientModInitializer;

public class RTMapClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// Anything touching net.minecraft.client.* must live in the client source set.
		ClientNetworking.init();
		ClientDebugCommand.init();
		ClientMap.init();
	}
}
