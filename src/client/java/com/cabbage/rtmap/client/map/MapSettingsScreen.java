package com.cabbage.rtmap.client.map;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.function.IntFunction;

import com.cabbage.rtmap.client.config.ClientConfig;
import com.cabbage.rtmap.client.config.ClientConfig.Corner;
import com.cabbage.rtmap.client.map.layer.MapLayer;
import com.cabbage.rtmap.client.map.layer.MapLayers;
import com.cabbage.rtmap.client.network.ClientSession;
import com.cabbage.rtmap.client.network.ManualSeed;
import com.cabbage.rtmap.client.ui.NavButton;
import com.cabbage.rtmap.client.ui.Theme;
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
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;

/**
 * Map settings. Categories on the left, a scrolling list of options on the right, and a search box on top that
 * looks through every category at once. Changes apply immediately and are saved when the screen closes.
 */
public final class MapSettingsScreen extends Screen {
	private static final int HEIGHT = 20;
	private static final int ROW_HEIGHT = 24;
	private static final int MAX_WIDGET_WIDTH = 200;
	private static final int MARGIN = 8;
	private static final int SIDE_WIDTH = 96;
	private static final int TITLE_BAR = 22;
	private static final int PANEL_TOP = 28;
	private static final int PANEL_PADDING = 4;
	private static final int SEARCH_TOP = PANEL_TOP + 6;
	private static final int LIST_TOP = PANEL_TOP + 28;
	private static final int BOTTOM_HEIGHT = 36;

	private enum Category {
		MINIMAP("minimap"),
		LAYERS("layers"),
		WAYPOINTS("waypoints"),
		SEED("seed");

		final String id;

		Category(String id) {
			this.id = id;
		}

		Text title() {
			return Text.translatable("option.rtmap.category." + id);
		}
	}

	/** One setting. {@code factory} builds its widget for a given width, so it always shows the current value. */
	private record Option(Category category, String labelKey, IntFunction<ClickableWidget> factory) {
		boolean matches(String query) {
			return Text.translatable(labelKey).getString().toLowerCase(Locale.ROOT).contains(query)
				|| category.title().getString().toLowerCase(Locale.ROOT).contains(query);
		}
	}

	/** Remembered while the game runs, so reopening the settings returns to the same page. */
	private static Category selectedCategory = Category.MINIMAP;

	private final Screen parent;
	private final List<Option> options = new ArrayList<>();
	private OptionList list;
	private String query = "";
	private int widgetWidth = MAX_WIDGET_WIDTH;

	public MapSettingsScreen(Screen parent) {
		super(Text.translatable("screen.rtmap.settings"));
		this.parent = parent;
		defineOptions();
	}

	private void add(Category category, String labelKey, IntFunction<ClickableWidget> factory) {
		options.add(new Option(category, labelKey, factory));
	}

	private void defineOptions() {
		add(Category.MINIMAP, "option.rtmap.minimap", w -> CyclingButtonWidget.onOffBuilder(ClientConfig.minimapEnabled())
			.build(0, 0, w, HEIGHT, Text.translatable("option.rtmap.minimap"), (button, value) -> ClientConfig.setMinimapEnabled(value)));
		add(Category.MINIMAP, "option.rtmap.corner", w -> CyclingButtonWidget.<Corner>builder(
				corner -> Text.translatable("option.rtmap.corner." + corner.name().toLowerCase(Locale.ROOT)))
			.values(Corner.values())
			.initially(ClientConfig.minimapCorner())
			.build(0, 0, w, HEIGHT, Text.translatable("option.rtmap.corner"), (button, value) -> ClientConfig.setMinimapCorner(value)));
		add(Category.MINIMAP, "option.rtmap.size", w -> CyclingButtonWidget.<Integer>builder(size -> Text.literal(size + " px"))
			.values(java.util.Arrays.stream(ClientConfig.MINIMAP_SIZES).boxed().toList())
			.initially(ClientConfig.minimapSize())
			.build(0, 0, w, HEIGHT, Text.translatable("option.rtmap.size"), (button, value) -> ClientConfig.setMinimapSize(value)));
		add(Category.MINIMAP, "option.rtmap.rotate", w -> CyclingButtonWidget.onOffBuilder(ClientConfig.minimapRotate())
			.build(0, 0, w, HEIGHT, Text.translatable("option.rtmap.rotate"), (button, value) -> ClientConfig.setMinimapRotate(value)));
		add(Category.MINIMAP, "option.rtmap.coordinates", w -> CyclingButtonWidget.onOffBuilder(ClientConfig.showCoordinates())
			.build(0, 0, w, HEIGHT, Text.translatable("option.rtmap.coordinates"), (button, value) -> ClientConfig.setShowCoordinates(value)));
		add(Category.MINIMAP, "option.rtmap.converted_coordinates", w -> CyclingButtonWidget.onOffBuilder(ClientConfig.showConvertedCoordinates())
			.build(0, 0, w, HEIGHT, Text.translatable("option.rtmap.converted_coordinates"),
				(button, value) -> ClientConfig.setShowConvertedCoordinates(value)));

		for (MapLayer layer : MapLayers.toggleable()) {
			String key = "layer.rtmap." + layer.id();
			add(Category.LAYERS, key, w -> CyclingButtonWidget.onOffBuilder(MapLayers.isEnabled(layer))
				.build(0, 0, w, HEIGHT, Text.translatable(key), (button, value) -> MapLayers.setEnabled(layer, value)));
		}
		add(Category.LAYERS, "option.rtmap.spawn_range_around_me", w -> CyclingButtonWidget.onOffBuilder(ClientConfig.spawnRangeAroundPlayer())
			.build(0, 0, w, HEIGHT, Text.translatable("option.rtmap.spawn_range_around_me"),
				(button, value) -> ClientConfig.setSpawnRangeAroundPlayer(value)));

		add(Category.WAYPOINTS, "option.rtmap.world_waypoints", w -> CyclingButtonWidget.onOffBuilder(ClientConfig.worldWaypoints())
			.build(0, 0, w, HEIGHT, Text.translatable("option.rtmap.world_waypoints"), (button, value) -> ClientConfig.setWorldWaypoints(value)));
		add(Category.WAYPOINTS, "option.rtmap.world_beams", w -> CyclingButtonWidget.onOffBuilder(ClientConfig.worldBeams())
			.build(0, 0, w, HEIGHT, Text.translatable("option.rtmap.world_beams"), (button, value) -> ClientConfig.setWorldBeams(value)));
		add(Category.WAYPOINTS, "option.rtmap.show_distance", w -> CyclingButtonWidget.onOffBuilder(ClientConfig.showDistance())
			.build(0, 0, w, HEIGHT, Text.translatable("option.rtmap.show_distance"), (button, value) -> ClientConfig.setShowDistance(value)));
		add(Category.WAYPOINTS, "option.rtmap.death_waypoints", w -> CyclingButtonWidget.onOffBuilder(ClientConfig.createDeathWaypoints())
			.build(0, 0, w, HEIGHT, Text.translatable("option.rtmap.death_waypoints"), (button, value) -> ClientConfig.setCreateDeathWaypoints(value)));
		add(Category.WAYPOINTS, "option.rtmap.other_dimension_waypoints", w -> CyclingButtonWidget.onOffBuilder(ClientConfig.showOtherDimensionWaypoints())
			.build(0, 0, w, HEIGHT, Text.translatable("option.rtmap.other_dimension_waypoints"),
				(button, value) -> ClientConfig.setShowOtherDimensionWaypoints(value)));

		add(Category.SEED, "option.rtmap.seed", w -> {
			ButtonWidget button = ButtonWidget.builder(seedLabel(), pressed ->
				client.setScreen(new TextPromptScreen(this, Text.translatable("option.rtmap.seed_prompt"),
					text -> ManualSeed.set(ManualSeed.parse(text)))))
				.dimensions(0, 0, w, HEIGHT).build();
			// A typed seed is only a fallback. While the game or the server provides one it would do nothing, so
			// the button is switched off rather than accepting a seed that is then ignored.
			button.active = ManualSeed.isLoaded() && !seedProvidedByGame();
			return button;
		});
		add(Category.SEED, "option.rtmap.seed_clear", w -> {
			ButtonWidget button = ButtonWidget.builder(Text.translatable("option.rtmap.seed_clear"), pressed -> {
				ManualSeed.clear();
				client.setScreen(new MapSettingsScreen(parent));
			}).dimensions(0, 0, w, HEIGHT).build();
			button.active = ManualSeed.get().isPresent();
			return button;
		});
	}

	/** Left edge of the content panel's inside: past the sidebar panel, a gap, and the panel's own padding. */
	private static int listX() {
		return MARGIN + SIDE_WIDTH + 2 * PANEL_PADDING + 4 + PANEL_PADDING;
	}

	private int listWidth() {
		return width - MARGIN - PANEL_PADDING - listX();
	}

	@Override
	protected void init() {
		int listX = listX();
		int listWidth = listWidth();
		widgetWidth = Math.max(100, Math.min(MAX_WIDGET_WIDTH, listWidth - 28));

		TextFieldWidget search = new TextFieldWidget(textRenderer, listX, SEARCH_TOP, listWidth, 18, Text.translatable("option.rtmap.search"));
		search.setPlaceholder(Text.translatable("option.rtmap.search"));
		search.setText(query);
		addDrawableChild(search);

		int y = PANEL_TOP + PANEL_PADDING;
		for (Category category : Category.values()) {
			// The open page is marked; while searching no page is, since the results come from all of them.
			addDrawableChild(new NavButton(MARGIN + PANEL_PADDING, y, SIDE_WIDTH, HEIGHT, category.title(),
				() -> query.trim().isEmpty() && selectedCategory == category, pressed -> {
					selectedCategory = category;
					// Choosing a page leaves the search, otherwise the click would seem to do nothing.
					query = "";
					search.setText("");
					rebuild();
				}));
			y += ROW_HEIGHT;
		}

		list = new OptionList(client, listWidth, height, LIST_TOP, height - BOTTOM_HEIGHT - PANEL_PADDING);
		list.setLeftPos(listX);
		list.setRenderBackground(false);
		list.setRenderHorizontalShadows(false);
		addDrawableChild(list);

		search.setChangedListener(text -> {
			query = text;
			rebuild();
		});
		setInitialFocus(search);

		addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, pressed -> close())
			.dimensions(width / 2 - 100, height - 28, 200, HEIGHT).build());

		rebuild();
	}

	private void rebuild() {
		list.clearRows();
		String needle = query.trim().toLowerCase(Locale.ROOT);

		if (needle.isEmpty()) {
			for (Option option : options) {
				if (option.category() == selectedCategory) {
					list.addRow(new OptionList.OptionRow(option.factory().apply(widgetWidth), widgetWidth));
				}
			}
		} else {
			for (Category category : Category.values()) {
				boolean headerAdded = false;
				for (Option option : options) {
					if (option.category() == category && option.matches(needle)) {
						if (!headerAdded) {
							list.addRow(new OptionList.HeaderRow(category.title()));
							headerAdded = true;
						}
						list.addRow(new OptionList.OptionRow(option.factory().apply(widgetWidth), widgetWidth));
					}
				}
			}
		}
		list.setScrollAmount(0);
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
		Theme.backdrop(context, width, height);
		Theme.bar(context, 0, 0, width, TITLE_BAR, true);
		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 7, Theme.TEXT);

		int panelHeight = height - BOTTOM_HEIGHT - PANEL_TOP;
		Theme.panel(context, MARGIN, PANEL_TOP, SIDE_WIDTH + 2 * PANEL_PADDING, panelHeight);
		Theme.panel(context, listX() - PANEL_PADDING, PANEL_TOP, listWidth() + 2 * PANEL_PADDING, panelHeight);
		super.render(context, mouseX, mouseY, delta);

		if (list.isEmpty()) {
			context.drawCenteredTextWithShadow(textRenderer, Text.translatable("option.rtmap.search_empty"),
				listX() + listWidth() / 2, height / 2, 0xA0A0A0);
		}
	}

	@Override
	public void close() {
		ClientConfig.saveIfDirty();
		client.setScreen(parent);
	}

	/** The options on the right. It scrolls with the mouse wheel when there are more rows than fit. */
	private static final class OptionList extends ElementListWidget<OptionList.Row> {
		OptionList(MinecraftClient client, int width, int height, int top, int bottom) {
			super(client, width, height, top, bottom, ROW_HEIGHT);
		}

		void clearRows() {
			clearEntries();
		}

		void addRow(Row row) {
			addEntry(row);
		}

		boolean isEmpty() {
			return getEntryCount() == 0;
		}

		@Override
		public int getRowWidth() {
			return width - 16;
		}

		@Override
		protected int getScrollbarPositionX() {
			return right - 6;
		}

		abstract static class Row extends ElementListWidget.Entry<Row> {
		}

		/** A section title, shown between groups of results while searching. */
		static final class HeaderRow extends Row {
			private final Text title;

			HeaderRow(Text title) {
				this.title = title;
			}

			@Override
			public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight,
				int mouseX, int mouseY, boolean hovered, float delta) {
				MinecraftClient client = MinecraftClient.getInstance();
				context.drawTextWithShadow(client.textRenderer, title, x + 4, y + (entryHeight - 8) / 2 + 2, 0xFFD27F);
			}

			@Override
			public List<? extends Element> children() {
				return List.of();
			}

			@Override
			public List<? extends Selectable> selectableChildren() {
				return List.of();
			}
		}

		static final class OptionRow extends Row {
			private final ClickableWidget widget;
			private final int widgetWidth;

			OptionRow(ClickableWidget widget, int widgetWidth) {
				this.widget = widget;
				this.widgetWidth = widgetWidth;
			}

			@Override
			public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight,
				int mouseX, int mouseY, boolean hovered, float delta) {
				widget.setX(x + (entryWidth - widgetWidth) / 2);
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
