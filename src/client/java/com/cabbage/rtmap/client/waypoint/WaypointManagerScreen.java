package com.cabbage.rtmap.client.waypoint;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.cabbage.rtmap.RTMap;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.Selectable;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.ElementListWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Lists every waypoint of the world with search and filters, and lets the player teleport to, edit, switch off
 * or delete each one.
 */
public final class WaypointManagerScreen extends Screen {
	private static final int ROW_HEIGHT = 24;
	private static final int ROW_WIDTH = 330;
	private static final int LIST_TOP = 44;
	private static final int BOTTOM_HEIGHT = 32;
	/** Marks "every group" in the group filter. */
	private static final String ALL_GROUPS = "";

	private final Screen parent;

	// Kept in fields so the filters survive the screen being rebuilt (resizing, or coming back from the editor).
	private String searchText = "";
	private boolean currentDimensionOnly;
	private String groupFilter = ALL_GROUPS;

	private ListWidget list;
	private ButtonWidget groupToggle;
	/** The result of the last export or import, shown at the top left. */
	private Text status = Text.empty();

	public WaypointManagerScreen(Screen parent) {
		super(Text.translatable("screen.rtmap.waypoints"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int center = width / 2;

		TextFieldWidget search = new TextFieldWidget(textRenderer, center - ROW_WIDTH / 2, 20, 120, 18, Text.translatable("waypoint.rtmap.search"));
		search.setPlaceholder(Text.translatable("waypoint.rtmap.search"));
		search.setText(searchText);
		search.setChangedListener(value -> {
			searchText = value;
			refresh();
		});
		addDrawableChild(search);

		addDrawableChild(CyclingButtonWidget.onOffBuilder(currentDimensionOnly)
			.build(center - ROW_WIDTH / 2 + 124, 19, 100, 20, Text.translatable("waypoint.rtmap.only_here"), (button, value) -> {
				currentDimensionOnly = value;
				refresh();
			}));

		List<String> groups = new ArrayList<>();
		groups.add(ALL_GROUPS);
		for (WaypointGroup group : WaypointStore.groups()) {
			groups.add(group.id);
		}
		if (!groups.contains(groupFilter)) {
			groupFilter = ALL_GROUPS;
		}
		addDrawableChild(CyclingButtonWidget.<String>builder(id -> id.isEmpty()
				? Text.translatable("waypoint.rtmap.all_groups") : WaypointStore.group(id).displayName())
			.values(groups)
			.initially(groupFilter)
			.build(center - ROW_WIDTH / 2 + 228, 19, ROW_WIDTH - 228, 20, Text.translatable("waypoint.rtmap.group"), (button, value) -> {
				groupFilter = value;
				refresh();
			}));

		list = new ListWidget(client, width, height, LIST_TOP, height - BOTTOM_HEIGHT);
		addDrawableChild(list);

		int bottom = height - 26;
		int x = center - ROW_WIDTH / 2;
		addDrawableChild(ButtonWidget.builder(Text.translatable("waypoint.rtmap.new"), pressed -> openEditorForNew())
			.dimensions(x, bottom, 46, 20).build());
		x += 48;
		addDrawableChild(ButtonWidget.builder(Text.translatable("waypoint.rtmap.new_group"), pressed ->
			client.setScreen(new TextPromptScreen(this, Text.translatable("waypoint.rtmap.new_group_prompt"), name -> {
				groupFilter = WaypointStore.addGroup(name).id;
			}))).dimensions(x, bottom, 62, 20).build());
		x += 64;
		groupToggle = addDrawableChild(ButtonWidget.builder(Text.empty(), pressed -> toggleSelectedGroup())
			.dimensions(x, bottom, 70, 20).build());
		x += 72;
		addDrawableChild(ButtonWidget.builder(Text.translatable("waypoint.rtmap.export"), pressed -> exportWaypoints())
			.dimensions(x, bottom, 46, 20).build());
		x += 48;
		addDrawableChild(ButtonWidget.builder(Text.translatable("waypoint.rtmap.import"), pressed -> importWaypoints())
			.dimensions(x, bottom, 46, 20).build());
		x += 48;
		addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, pressed -> close())
			.dimensions(x, bottom, ROW_WIDTH - (x - (center - ROW_WIDTH / 2)), 20).build());

		refresh();
	}

	private void openEditorForNew() {
		if (client.player == null || client.world == null) {
			return;
		}
		client.setScreen(new WaypointEditorScreen(this, null, client.player.getBlockX(), client.player.getBlockY(),
			client.player.getBlockZ(), Waypoints.dimensionId(client.world)));
	}

	private void exportWaypoints() {
		try {
			Path file = WaypointFiles.export();
			status = Text.translatable("waypoint.rtmap.exported", file.getFileName().toString());
		} catch (IOException e) {
			RTMap.LOGGER.warn("Could not export waypoints", e);
			status = Text.translatable("waypoint.rtmap.export_failed").formatted(Formatting.RED);
		}
	}

	private void importWaypoints() {
		try {
			WaypointFiles.ImportResult result = WaypointFiles.importAll();
			status = result.files() == 0
				? Text.translatable("waypoint.rtmap.import_none", WaypointFiles.importDir().toString())
				: Text.translatable("waypoint.rtmap.imported", result.added(), result.skipped());
			refresh();
		} catch (IOException e) {
			RTMap.LOGGER.warn("Could not import waypoints", e);
			status = Text.translatable("waypoint.rtmap.import_failed").formatted(Formatting.RED);
		}
	}

	private void toggleSelectedGroup() {
		WaypointGroup group = WaypointStore.group(groupFilter);
		if (group != null) {
			group.enabled = !group.enabled;
			WaypointStore.markChanged();
			refresh();
		}
	}

	private void refresh() {
		if (list == null) {
			return;
		}
		String dimension = client.world == null ? null : Waypoints.dimensionId(client.world);
		String query = searchText.trim().toLowerCase(Locale.ROOT);

		List<Waypoint> shown = new ArrayList<>();
		for (Waypoint waypoint : WaypointStore.waypoints()) {
			if (currentDimensionOnly && dimension != null && !waypoint.dimension.equals(dimension)) {
				continue;
			}
			if (!groupFilter.equals(ALL_GROUPS) && !waypoint.group.equals(groupFilter)) {
				continue;
			}
			if (!query.isEmpty() && !waypoint.name.toLowerCase(Locale.ROOT).contains(query)) {
				continue;
			}
			shown.add(waypoint);
		}
		shown.sort(Comparator.comparing((Waypoint waypoint) -> waypoint.name.toLowerCase(Locale.ROOT)));
		list.setWaypoints(shown);

		WaypointGroup group = WaypointStore.group(groupFilter);
		groupToggle.active = group != null;
		groupToggle.setMessage(group == null
			? Text.translatable("waypoint.rtmap.group_toggle")
			: Text.translatable("waypoint.rtmap.group_toggle").append(": ").append(ScreenTexts.onOrOff(group.enabled)));
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		// The manager key closes the manager again, but not while typing in the search box.
		if (Waypoints.managerKey().matchesKey(keyCode, scanCode) && !(getFocused() instanceof TextFieldWidget)) {
			close();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		renderBackground(context);
		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 6, 0xFFFFFF);
		int statusWidth = Math.max(40, width / 2 - textRenderer.getWidth(title) / 2 - 12);
		context.drawText(textRenderer, textRenderer.trimToWidth(status.getString(), statusWidth), 6, 6, 0x80FF80, false);
		super.render(context, mouseX, mouseY, delta);

		if (list.children().isEmpty()) {
			context.drawCenteredTextWithShadow(textRenderer, Text.translatable("waypoint.rtmap.empty"), width / 2, height / 2, 0xA0A0A0);
		}
	}

	@Override
	public void close() {
		client.setScreen(parent);
	}

	private final class ListWidget extends ElementListWidget<ListWidget.Row> {
		ListWidget(MinecraftClient client, int width, int height, int top, int bottom) {
			super(client, width, height, top, bottom, ROW_HEIGHT);
		}

		void setWaypoints(List<Waypoint> waypoints) {
			double scroll = getScrollAmount();
			List<Row> rows = new ArrayList<>();
			for (Waypoint waypoint : waypoints) {
				rows.add(new Row(waypoint));
			}
			replaceEntries(rows);
			setScrollAmount(scroll);
		}

		@Override
		public int getRowWidth() {
			return ROW_WIDTH;
		}

		@Override
		protected int getScrollbarPositionX() {
			return width / 2 + ROW_WIDTH / 2 + 6;
		}

		private final class Row extends ElementListWidget.Entry<Row> {
			private final Waypoint waypoint;
			private final ButtonWidget teleport;
			private final ButtonWidget edit;
			private final ButtonWidget toggle;
			private final ButtonWidget delete;
			private final List<ButtonWidget> buttons;
			private boolean confirmingDelete;

			Row(Waypoint waypoint) {
				this.waypoint = waypoint;
				teleport = ButtonWidget.builder(Text.translatable("waypoint.rtmap.teleport"), pressed -> {
					Waypoints.teleport(waypoint);
					client.setScreen(null);
				}).size(34, 18).build();
				edit = ButtonWidget.builder(Text.translatable("waypoint.rtmap.edit"), pressed ->
					client.setScreen(new WaypointEditorScreen(WaypointManagerScreen.this, waypoint, waypoint.x, waypoint.y,
						waypoint.z, waypoint.dimension))).size(34, 18).build();
				toggle = ButtonWidget.builder(Text.empty(), pressed -> {
					waypoint.enabled = !waypoint.enabled;
					WaypointStore.markChanged();
				}).size(34, 18).build();
				delete = ButtonWidget.builder(Text.empty(), pressed -> {
					// Two clicks: the first only asks, so one slip cannot lose a waypoint.
					if (confirmingDelete) {
						WaypointStore.remove(waypoint);
						refresh();
					} else {
						confirmingDelete = true;
					}
				}).size(34, 18).build();
				buttons = List.of(teleport, edit, toggle, delete);
			}

			@Override
			public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight,
				int mouseX, int mouseY, boolean hovered, float delta) {
				// What the player sees is the real outcome: a waypoint in a switched-off group is off whatever its own
				// switch says. Its own switch is kept, so it comes back as it was when the group is switched on again.
				WaypointGroup waypointGroup = WaypointStore.group(waypoint.group);
				boolean groupOff = waypointGroup != null && !waypointGroup.enabled;
				boolean shown = WaypointStore.isVisible(waypoint);
				toggle.setMessage(ScreenTexts.onOrOff(shown));
				toggle.active = !groupOff;
				delete.setMessage(confirmingDelete
					? Text.translatable("waypoint.rtmap.confirm").formatted(net.minecraft.util.Formatting.RED)
					: Text.translatable("waypoint.rtmap.delete"));

				int buttonX = x + entryWidth - 4 * 34 - 3 * 2;
				for (ButtonWidget button : buttons) {
					button.setX(buttonX);
					button.setY(y + 3);
					button.render(context, mouseX, mouseY, delta);
					buttonX += 36;
				}

				context.fill(x, y + 3, x + 4, y + entryHeight - 1, 0xFF000000 | waypoint.color);
				int textWidth = entryWidth - 4 * 36 - 12;
				int nameColor = shown ? 0xFFFFFF : 0x808080;
				context.drawText(textRenderer, textRenderer.trimToWidth(waypoint.name, textWidth), x + 8, y + 3, nameColor, false);
				WaypointGroup group = WaypointStore.group(waypoint.group);
				String detail = Waypoints.coordinates(waypoint) + "  " + shortDimension(waypoint.dimension)
					+ (group == null ? "" : "  " + group.displayName().getString())
					+ (groupOff ? "  " + Text.translatable("waypoint.rtmap.group_off").getString() : "");
				context.drawText(textRenderer, textRenderer.trimToWidth(detail, textWidth), x + 8, y + 13, 0x909090, false);
			}

			@Override
			public List<? extends Element> children() {
				return buttons;
			}

			@Override
			public List<? extends Selectable> selectableChildren() {
				return buttons;
			}
		}
	}

	private static String shortDimension(String id) {
		return switch (id) {
			case "minecraft:overworld" -> Text.translatable("waypoint.rtmap.dimension.overworld").getString();
			case "minecraft:the_nether" -> Text.translatable("waypoint.rtmap.dimension.nether").getString();
			case "minecraft:the_end" -> Text.translatable("waypoint.rtmap.dimension.end").getString();
			default -> id;
		};
	}
}
