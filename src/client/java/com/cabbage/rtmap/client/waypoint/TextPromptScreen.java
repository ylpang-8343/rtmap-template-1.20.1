package com.cabbage.rtmap.client.waypoint;

import java.util.function.Consumer;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;

/** A small "type a name" dialog. */
public final class TextPromptScreen extends Screen {
	private static final int WIDTH = 200;

	private final Screen parent;
	private final Text prompt;
	private final Consumer<String> onConfirm;
	private String text = "";
	private TextFieldWidget field;

	public TextPromptScreen(Screen parent, Text prompt, Consumer<String> onConfirm) {
		super(prompt);
		this.parent = parent;
		this.prompt = prompt;
		this.onConfirm = onConfirm;
	}

	@Override
	protected void init() {
		int left = width / 2 - WIDTH / 2;
		int top = height / 2 - 30;

		field = new TextFieldWidget(textRenderer, left, top + 12, WIDTH, 18, prompt);
		field.setMaxLength(32);
		field.setText(text);
		field.setChangedListener(value -> text = value);
		addDrawableChild(field);
		setInitialFocus(field);

		addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, pressed -> confirm())
			.dimensions(left, top + 38, WIDTH / 2 - 2, 20).build());
		addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, pressed -> close())
			.dimensions(left + WIDTH / 2 + 2, top + 38, WIDTH / 2 - 2, 20).build());
	}

	private void confirm() {
		String value = text.trim();
		if (!value.isEmpty()) {
			onConfirm.accept(value);
		}
		close();
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		// Enter confirms (257 is Enter, 335 is the keypad Enter).
		if (keyCode == 257 || keyCode == 335) {
			confirm();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		renderBackground(context);
		context.drawTextWithShadow(textRenderer, prompt, width / 2 - WIDTH / 2, height / 2 - 30, 0xFFFFFF);
		super.render(context, mouseX, mouseY, delta);
	}

	@Override
	public void close() {
		client.setScreen(parent);
	}
}
