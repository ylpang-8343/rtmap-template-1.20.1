package com.cabbage.rtmap.client.map;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/** Wires the map into the game: key binding, chunk events, saving and cleanup. */
public final class ClientMap {
	private static KeyBinding openKey;

	private ClientMap() {
	}

	public static KeyBinding openKey() {
		return openKey;
	}

	public static void init() {
		openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.rtmap.open_map", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_M, "key.categories.rtmap"));

		ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> MapUpdater.queue(world, chunk.getPos()));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openKey.wasPressed()) {
				if (client.world != null && client.currentScreen == null) {
					client.setScreen(new FullscreenMapScreen());
				}
			}
			MapUpdater.tick(client);
		});

		// Leaving a world: write out unsaved regions and free the textures. MapUpdater also notices a new
		// connection on its own, so a missed or reordered event cannot leak one world's map into the next.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> MapUpdater.endSession());

		// Closing the game from inside a world: make sure the last writes reach the disk.
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			MapCache.saveAll();
			MapStorage.flushAndWait();
		});
	}
}
