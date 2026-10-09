package com.cabbage.rtmap.client.map.layer;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.Vec3d;

/** The player: a dot with a short line showing which way they face. Always drawn, on top of everything else. */
public final class PlayerLayer implements MapLayer {
	@Override
	public String id() {
		return "player";
	}

	@Override
	public boolean toggleable() {
		return false;
	}

	@Override
	public void render(DrawContext context, LayerView view) {
		MinecraftClient client = MinecraftClient.getInstance();
		ClientPlayerEntity player = client.player;
		if (player == null) {
			return;
		}

		Vec3d position = player.getLerpedPos(client.getTickDelta());
		int x = (int) Math.round(view.x(position.x));
		int y = (int) Math.round(view.y(position.z));

		// North-up facing: yaw 0 looks south (+z). A rotated minimap has already turned the view, so this stays correct.
		double yaw = Math.toRadians(player.getYaw(client.getTickDelta()));
		double dx = -Math.sin(yaw);
		double dz = Math.cos(yaw);
		for (int step = 2; step <= 8; step++) {
			int px = (int) Math.round(x + dx * step);
			int py = (int) Math.round(y + dz * step);
			context.fill(px - 1, py - 1, px + 1, py + 1, 0xFFFFFFFF);
		}
		context.fill(x - 3, y - 3, x + 3, y + 3, 0xFFFFFFFF);
		context.fill(x - 2, y - 2, x + 2, y + 2, 0xFFE03030);
	}
}
