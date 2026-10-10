package com.cabbage.rtmap.client.map;

import java.util.List;
import java.util.OptionalLong;

import com.cabbage.rtmap.client.config.ClientConfig;
import com.cabbage.rtmap.client.config.ClientConfig.Corner;
import com.cabbage.rtmap.client.map.layer.MapLayer;
import com.cabbage.rtmap.client.map.layer.MapLayers;
import com.cabbage.rtmap.client.network.ClientSession;
import com.cabbage.rtmap.client.network.ManualSeed;
import com.cabbage.rtmap.client.waypoint.TextPromptScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.Selectable;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.ElementListWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;

/**
 * Minimap and layer settings, in a scrolling list so it fits however large the GUI is. Changes apply
 * immediately and are saved when the screen closes.
 */
public final class MapSettingsScreen extends Screen {
	private static final int WIDTH = 200;
	private static final int HEIGHT = 20;
	private static final int ROW_HEIGHT = 24;
	private static final int TOP = 28;
	private static final int BOTTOM_HEIGHT = 32;

	private final Screen parent;

	public MapSettingsScreen(Screen parent) {
		super(Text.translatable("screen.rtmap.settings"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		OptionList list = new OptionList(client, width, height, TOP, height - BOTTOM_HEIGHT);

		list.add(CyclingButtonWidget.onOffBuilder(ClientConfig.minimapEnabled())
			.build(0, 0, WIDTH, HEIGHT, Text.translatable("option.rtmap.minimap"),
				(button, value) -> ClientConfig.setMinimapEnabled(value)));

		list.add(CyclingButtonWidget.<Corner>builder(corner -> Text.translatable("option.rtmap.corner." + corner.name().toLowerCase()))
			.values(Corner.values())
			.initially(ClientConfig.minimapCorner())
			.build(0, 0, WIDTH, HEIGHT, Text.translatable("option.rtmap.corner"),
				(button, value) -> ClientConfig.setMinimapCorner(value)));

		List<Integer> sizes = java.util.Arrays.stream(ClientConfig.MINIMAP_SIZES).boxed().toList();
		list.add(CyclingButtonWidget.<Integer>builder(size -> Text.literal(size + " px"))
			.values(sizes)
			.initially(ClientConfig.minimapSize())
			.build(0, 0, WIDTH, HEIGHT, Text.translatable("option.rtmap.size"),
				(button, value) -> ClientConfig.setMinimapSize(value)));

		list.add(CyclingButtonWidget.onOffBuilder(ClientConfig.minimapRotate())
			.build(0, 0, WIDTH, HEIGHT, Text.translatable("option.rtmap.rotate"),
				(button, value) -> ClientConfig.setMinimapRotate(value)));

		list.add(CyclingButtonWidget.onOffBuilder(ClientConfig.showCoordinates())
			.build(0, 0, WIDTH, HEIGHT, Text.translatable("option.rtmap.coordinates"),
				(button, value) -> ClientConfig.setShowCoordinates(value)));

		list.add(CyclingButtonWidget.onOffBuilder(ClientConfig.showConvertedCoordinates())
			.build(0, 0, WIDTH, HEIGHT, Text.translatable("option.rtmap.converted_coordinates"),
				(button, value) -> ClientConfig.setShowConvertedCoordinates(value)));

		list.add(CyclingButtonWidget.onOffBuilder(ClientConfig.showOtherDimensionWaypoints())
			.build(0, 0, WIDTH, HEIGHT, Text.translatable("option.rtmap.other_dimension_waypoints"),
				(button, value) -> ClientConfig.setShowOtherDimensionWaypoints(value)));

		list.add(CyclingButtonWidget.onOffBuilder(ClientConfig.worldWaypoints())
			.build(0, 0, WIDTH, HEIGHT, Text.translatable("option.rtmap.world_waypoints"),
				(button, value) -> ClientConfig.setWorldWaypoints(value)));

		list.add(CyclingButtonWidget.onOffBuilder(ClientConfig.worldBeams())
			.build(0, 0, WIDTH, HEIGHT, Text.translatable("option.rtmap.world_beams"),
				(button, value) -> ClientConfig.setWorldBeams(value)));

		list.add(CyclingButtonWidget.onOffBuilder(ClientConfig.showDistance())
			.build(0, 0, WIDTH, HEIGHT, Text.translatable("option.rtmap.show_distance"),
				(button, value) -> ClientConfig.setShowDistance(value)));

		list.add(CyclingButtonWidget.onOffBuilder(ClientConfig.createDeathWaypoints())
			.build(0, 0, WIDTH, HEIGHT, Text.translatable("option.rtmap.death_waypoints"),
				(button, value) -> ClientConfig.setCreateDeathWaypoints(value)));

		ButtonWidget seedButton = ButtonWidget.builder(seedLabel(), pressed ->
			client.setScreen(new TextPromptScreen(this, Text.translatable("option.rtmap.seed_prompt"),
				text -> ManualSeed.set(ManualSeed.parse(text)))))
			.dimensions(0, 0, WIDTH, HEIGHT).build();
		// A typed seed is only a fallback. While the game or the server provides one it would do nothing, so
		// the button is switched off rather than accepting a seed that is then ignored.
		seedButton.active = ManualSeed.isLoaded() && !seedProvidedByGame();
		list.add(seedButton);

		ButtonWidget clearSeed = ButtonWidget.builder(Text.translatable("option.rtmap.seed_clear"), pressed -> {
			ManualSeed.clear();
			client.setScreen(new MapSettingsScreen(parent));
		}).dimensions(0, 0, WIDTH, HEIGHT).build();
		clearSeed.active = ManualSeed.get().isPresent();
		list.add(clearSeed);

		for (MapLayer layer : MapLayers.toggleable()) {
			list.add(CyclingButtonWidget.onOffBuilder(MapLayers.isEnabled(layer))
				.build(0, 0, WIDTH, HEIGHT, Text.translatable("layer.rtmap." + layer.id()),
					(button, value) -> MapLayers.setEnabled(layer, value)));
		}
		addDrawableChild(list);

		addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, pressed -> close())
			.dimensions(width / 2 - WIDTH / 2, height - 26, WIDTH, HEIGHT).build());
	}

	private static boolean seedProvidedByGame() {
		return ClientSession.seedSource() == ClientSession.SeedSource.LOCAL
			|| ClientSession.seedSource() == ClientSession.SeedSource.SERVER;
	}

	/** "Seed: not set", or the typed seed. A seed from the game or server is shown as such and cannot be replaced here. */
	private static Text seedLabel() {
		OptionalLong typed = ManualSeed.get();
		Text value;
		if (seedProvidedByGame()) {
			value = Text.translatable("option.rtmap.seed_from_game");
		} else if (typed.isPresent()) {
			value = Text.literal(Long.toString(typed.getAsLong()));
		} else {
			value = Text.translatable("option.rtmap.seed_not_set");
		}
		return Text.translatable("option.rtmap.seed").append(": ").append(value);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		renderBackground(context);
		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 10, 0xFFFFFF);
		super.render(context, mouseX, mouseY, delta);
	}

	@Override
	public void close() {
		ClientConfig.saveIfDirty();
		client.setScreen(parent);
	}

	/** One option per row; the list scrolls with the mouse wheel when there are more rows than fit. */
	private static final class OptionList extends ElementListWidget<OptionList.Row> {
		OptionList(MinecraftClient client, int width, int height, int top, int bottom) {
			super(client, width, height, top, bottom, ROW_HEIGHT);
		}

		void add(ClickableWidget widget) {
			addEntry(new Row(widget));
		}

		@Override
		public int getRowWidth() {
			return WIDTH + 10;
		}

		@Override
		protected int getScrollbarPositionX() {
			return width / 2 + WIDTH / 2 + 12;
		}

		private static final class Row extends ElementListWidget.Entry<Row> {
			private final ClickableWidget widget;

			Row(ClickableWidget widget) {
				this.widget = widget;
			}

			@Override
			public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight,
				int mouseX, int mouseY, boolean hovered, float delta) {
				widget.setX(x + (entryWidth - WIDTH) / 2);
				widget.setY(y + 2);
				widget.render(context, mouseX, mouseY, delta);
			}

			@Override
			public List<? extends Element> children() {
				return List.of(widget);
			}

			@Override
			public List<? extends Selectable> selectableChildren() {
				return List.of(widget);
			}
		}
	}
}
