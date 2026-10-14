package com.cabbage.rtmap.client.ui;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * A square button showing a picture. Its name appears as a tooltip, and for a switch it lights up while on.
 * The icon and the name are asked for again every frame, so they always follow the current state.
 */
public final class IconButton extends ButtonWidget {
	private final Supplier<Icon> icon;
	private final Supplier<Text> label;
	private final BooleanSupplier lit;
	private String shownLabel = "";
	private boolean isSwitch = true;

	public IconButton(int x, int y, int size, Supplier<Icon> icon, Supplier<Text> label, BooleanSupplier lit, PressAction onPress) {
		super(x, y, size, size, label.get(), onPress, DEFAULT_NARRATION_SUPPLIER);
		this.icon = icon;
		this.label = label;
		this.lit = lit;
	}

	/** A button that is never shown as switched on (an action rather than a switch). */
	public static IconButton action(int x, int y, int size, Icon icon, Text label, PressAction onPress) {
		return new IconButton(x, y, size, () -> icon, () -> label, () -> false, onPress).asAction();
	}

	@Override
	public void renderButton(DrawContext context, int mouseX, int mouseY, float delta) {
		Text text = label.get();
		String plain = text.getString();
		if (!plain.equals(shownLabel)) {
			shownLabel = plain;
			setMessage(text);
			setTooltip(Tooltip.of(text));
		}

		boolean on = lit.getAsBoolean();
		int fill = !active ? Theme.BUTTON_DISABLED : isHovered() ? Theme.BUTTON_HOVER : on ? Theme.BUTTON_ON : Theme.BUTTON_OFF;
		context.fill(getX(), getY(), getX() + width, getY() + height, fill);
		context.drawBorder(getX(), getY(), width, height, on && active ? Theme.ACCENT : Theme.BORDER);

		// A switch that is off is dimmed; an action button always shows in full colour.
		boolean bright = active && (on || !isSwitch);
		icon.get().draw(context, getX() + (width - Icon.SIZE) / 2, getY() + (height - Icon.SIZE) / 2, bright);
	}

	/** Marks this button as an action: its icon is not dimmed when it is "off". */
	private IconButton asAction() {
		isSwitch = false;
		return this;
	}
}
