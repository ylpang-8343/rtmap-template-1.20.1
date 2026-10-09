package com.cabbage.rtmap.client.map;

import java.util.List;

import com.cabbage.rtmap.client.config.ClientConfig;
import com.cabbage.rtmap.client.config.ClientConfig.Corner;
import com.cabbage.rtmap.client.map.layer.MapLayer;
import com.cabbage.rtmap.client.map.layer.MapLayers;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;

/** Minimap and layer settings. Changes apply immediately and are saved when the screen closes. */
public final class MapSettingsScreen extends Screen {
	private static final int WIDTH = 200;
	private static final int HEIGHT = 20;
	private static final int GAP = 4;

	private final Screen parent;

	public MapSettingsScreen(Screen parent) {
		super(Text.translatable("screen.rtmap.settings"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int x = width / 2 - WIDTH / 2;
		int y = 40;

		addDrawableChild(CyclingButtonWidget.onOffBuilder(ClientConfig.minimapEnabled())
			.build(x, y, WIDTH, HEIGHT, Text.translatable("option.rtmap.minimap"),
				(button, value) -> ClientConfig.setMinimapEnabled(value)));
		y += HEIGHT + GAP;

		addDrawableChild(CyclingButtonWidget.<Corner>builder(corner -> Text.translatable("option.rtmap.corner." + corner.name().toLowerCase()))
			.values(Corner.values())
			.initially(ClientConfig.minimapCorner())
			.build(x, y, WIDTH, HEIGHT, Text.translatable("option.rtmap.corner"),
				(button, value) -> ClientConfig.setMinimapCorner(value)));
		y += HEIGHT + GAP;

		List<Integer> sizes = java.util.Arrays.stream(ClientConfig.MINIMAP_SIZES).boxed().toList();
		addDrawableChild(CyclingButtonWidget.<Integer>builder(size -> Text.literal(size + " px"))
			.values(sizes)
			.initially(ClientConfig.minimapSize())
			.build(x, y, WIDTH, HEIGHT, Text.translatable("option.rtmap.size"),
				(button, value) -> ClientConfig.setMinimapSize(value)));
		y += HEIGHT + GAP;

		addDrawableChild(CyclingButtonWidget.onOffBuilder(ClientConfig.minimapRotate())
			.build(x, y, WIDTH, HEIGHT, Text.translatable("option.rtmap.rotate"),
				(button, value) -> ClientConfig.setMinimapRotate(value)));
		y += HEIGHT + GAP;

		addDrawableChild(CyclingButtonWidget.onOffBuilder(ClientConfig.showCoordinates())
			.build(x, y, WIDTH, HEIGHT, Text.translatable("option.rtmap.coordinates"),
				(button, value) -> ClientConfig.setShowCoordinates(value)));
		y += HEIGHT + GAP;

		for (MapLayer layer : MapLayers.toggleable()) {
			addDrawableChild(CyclingButtonWidget.onOffBuilder(MapLayers.isEnabled(layer))
				.build(x, y, WIDTH, HEIGHT, Text.translatable("layer.rtmap." + layer.id()),
					(button, value) -> MapLayers.setEnabled(layer, value)));
			y += HEIGHT + GAP;
		}

		y += GAP * 2;
		addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, pressed -> close())
			.dimensions(x, y, WIDTH, HEIGHT).build());
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		renderBackground(context);
		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 20, 0xFFFFFF);
		super.render(context, mouseX, mouseY, delta);
	}

	@Override
	public void close() {
		ClientConfig.saveIfDirty();
		client.setScreen(parent);
	}
}
