package com.cabbage.rtmap.client.map;

import com.cabbage.rtmap.client.config.ClientConfig;
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
	private static final int CONFIG_SAVE_INTERVAL_TICKS = 100;

	private static KeyBinding openKey;
	private static int ticksSinceConfigSave;

	private ClientMap() {
	}

	public static KeyBinding openKey() {
		return openKey;
	}

	public static void init() {
		openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.rtmap.open_map", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_M, "key.categories.rtmap"));

		ClientConfig.load();
		Minimap.init();

		ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> MapUpdater.queue(world, chunk.getPos()));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openKey.wasPressed()) {
				if (client.world != null && client.currentScreen == null) {
					client.setScreen(new FullscreenMapScreen());
				}
			}
			MapUpdater.tick(client);

			// Settings changed by key presses (minimap zoom, toggle) are written out every few seconds.
			if (++ticksSinceConfigSave >= CONFIG_SAVE_INTERVAL_TICKS) {
				ticksSinceConfigSave = 0;
				ClientConfig.saveIfDirty();
			}
		});

		// Leaving a world: write out unsaved regions and free the textures. MapUpdater also notices a new
		// connection on its own, so a missed or reordered event cannot leak one world's map into the next.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> MapUpdater.endSession());

		// Closing the game from inside a world: make sure the last writes reach the disk.
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			ClientConfig.saveIfDirty();
			MapCache.saveAll();
			MapStorage.flushAndWait();
		});
	}
}
