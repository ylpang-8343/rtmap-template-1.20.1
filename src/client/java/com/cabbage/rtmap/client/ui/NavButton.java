package com.cabbage.rtmap.client.ui;

import java.util.function.BooleanSupplier;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/** A flat, left-aligned button for a list of pages. The open page is marked with a gold bar. */
public final class NavButton extends ButtonWidget {
	private final BooleanSupplier selected;

	public NavButton(int x, int y, int width, int height, Text label, BooleanSupplier selected, PressAction onPress) {
		super(x, y, width, height, label, onPress, DEFAULT_NARRATION_SUPPLIER);
		this.selected = selected;
	}

	@Override
	public void renderButton(DrawContext context, int mouseX, int mouseY, float delta) {
		boolean on = selected.getAsBoolean();
		int fill = isHovered() ? Theme.BUTTON_HOVER : on ? Theme.BUTTON_ON : Theme.BUTTON_OFF;
		context.fill(getX(), getY(), getX() + width, getY() + height, fill);
		if (on) {
			context.fill(getX(), getY(), getX() + 2, getY() + height, Theme.ACCENT);
		}
		MinecraftClient client = MinecraftClient.getInstance();
		context.drawText(client.textRenderer, getMessage(), getX() + 8, getY() + (height - 8) / 2, on ? Theme.TEXT : Theme.TEXT_MUTED, false);
	}
}
