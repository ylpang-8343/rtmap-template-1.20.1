package com.cabbage.rtmap.client.waypoint;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.registry.RegistryKey;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.world.World;

/** Create a waypoint, or change an existing one. Nothing is saved until Save is pressed. */
public final class WaypointEditorScreen extends Screen {
	private static final int WIDTH = 240;
	private static final int LABEL_COLOR = 0xA0A0A0;

	private final Screen parent;
	/** The waypoint being edited, or null when creating a new one. */
	private final Waypoint existing;

	// The editor keeps its own copy of the values, so resizing the window (which rebuilds the widgets) loses nothing.
	private String nameText;
	private String xText;
	private String yText;
	private String zText;
	private String dimension;
	private String group;
	private int color;
	private boolean enabled;

	private CyclingButtonWidget<Integer> colorButton;

	/** For an existing waypoint the position arguments are ignored; it supplies its own. */
	public WaypointEditorScreen(Screen parent, Waypoint existing, int x, int y, int z, String dimension) {
		super(Text.translatable(existing == null ? "screen.rtmap.waypoint.new" : "screen.rtmap.waypoint.edit"));
		this.parent = parent;
		this.existing = existing;
		if (existing == null) {
			this.nameText = "";
			this.xText = Integer.toString(x);
			this.yText = Integer.toString(y);
			this.zText = Integer.toString(z);
			this.dimension = dimension;
			this.group = WaypointGroup.DEFAULT_ID;
			this.color = Waypoints.randomColor();
			this.enabled = true;
		} else {
			this.nameText = existing.name;
			this.xText = Integer.toString(existing.x);
			this.yText = Integer.toString(existing.y);
			this.zText = Integer.toString(existing.z);
			this.dimension = existing.dimension;
			this.group = existing.group;
			this.color = existing.color;
			this.enabled = existing.enabled;
		}
	}

	private static boolean isIntegerText(String text) {
		return text.isEmpty() || text.equals("-") || text.matches("-?\\d{1,9}");
	}

	private static int parse(String text) {
		try {
			return Integer.parseInt(text);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	@Override
	protected void init() {
		int left = width / 2 - WIDTH / 2;
		int y = 28;

		TextFieldWidget nameField = new TextFieldWidget(textRenderer, left, y + 10, WIDTH, 18, Text.translatable("waypoint.rtmap.name"));
		nameField.setMaxLength(48);
		nameField.setText(nameText);
		nameField.setChangedListener(value -> nameText = value);
		addDrawableChild(nameField);
		if (existing == null) {
			setInitialFocus(nameField);
		}
		y += 34;

		int fieldWidth = (WIDTH - 8) / 3;
		addCoordinateField(left, y + 10, fieldWidth, xText, value -> xText = value);
		addCoordinateField(left + fieldWidth + 4, y + 10, fieldWidth, yText, value -> yText = value);
		addCoordinateField(left + 2 * (fieldWidth + 4), y + 10, fieldWidth, zText, value -> zText = value);
		y += 34;

		int half = WIDTH / 2 - 2;
		addDrawableChild(CyclingButtonWidget.<String>builder(this::dimensionName)
			.values(dimensions())
			.initially(dimension)
			.build(left, y, half, 20, Text.translatable("waypoint.rtmap.dimension"), (button, value) -> dimension = value));
		addDrawableChild(CyclingButtonWidget.<String>builder(id -> WaypointStore.group(id).displayName())
			.values(groupIds())
			.initially(group)
			.build(left + half + 4, y, half, 20, Text.translatable("waypoint.rtmap.group"), (button, value) -> group = value));
		y += 24;

		String counterpart = DimensionScale.counterpart(dimension);
		if (counterpart != null) {
			// Moves the waypoint to the other dimension of the pair, dividing or multiplying the position by 8.
			addDrawableChild(ButtonWidget.builder(Text.translatable("waypoint.rtmap.convert", dimensionName(counterpart),
					DimensionScale.OVERWORLD.equals(counterpart) ? "\u00d78" : "\u00f78"),
				pressed -> convertTo(counterpart)).dimensions(left, y, WIDTH, 20).build());
			y += 24;
		}

		List<Integer> palette = new ArrayList<>();
		for (int swatch : Waypoints.PALETTE) {
			palette.add(swatch);
		}
		if (!palette.contains(color)) {
			palette.add(color);
		}
		colorButton = addDrawableChild(CyclingButtonWidget.<Integer>builder(swatch -> Text.literal("████").styled(style -> style.withColor(swatch)))
			.values(palette)
			.initially(color)
			.build(left, y, half + 30, 20, Text.translatable("waypoint.rtmap.color"), (button, value) -> color = value));
		addDrawableChild(ButtonWidget.builder(Text.translatable("waypoint.rtmap.randomize"), pressed -> {
			color = Waypoints.randomColor();
			colorButton.setValue(color);
		}).dimensions(left + half + 34, y, WIDTH - half - 34, 20).build());
		y += 24;

		addDrawableChild(CyclingButtonWidget.onOffBuilder(enabled)
			.build(left, y, half, 20, Text.translatable("waypoint.rtmap.enabled"), (button, value) -> enabled = value));
		addDrawableChild(ButtonWidget.builder(Text.translatable("waypoint.rtmap.use_my_position"), pressed -> useMyPosition())
			.dimensions(left + half + 4, y, half, 20).build());
		y += 30;

		int buttons = existing == null ? 2 : 4;
		int buttonWidth = (WIDTH - 4 * (buttons - 1)) / buttons;
		int x = left;
		addDrawableChild(ButtonWidget.builder(Text.translatable("waypoint.rtmap.save"), pressed -> save())
			.dimensions(x, y, buttonWidth, 20).build());
		x += buttonWidth + 4;
		addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, pressed -> close())
			.dimensions(x, y, buttonWidth, 20).build());
		if (existing != null) {
			x += buttonWidth + 4;
			// Opens the chat box with the text filled in; the player decides whether to send it.
			addDrawableChild(ButtonWidget.builder(Text.translatable("waypoint.rtmap.share"), pressed ->
				client.setScreen(new ChatScreen(WaypointSharing.format(existing))))
				.dimensions(x, y, buttonWidth, 20).build());
			x += buttonWidth + 4;
			addDrawableChild(ButtonWidget.builder(Text.translatable("waypoint.rtmap.delete"), pressed -> {
				WaypointStore.remove(existing);
				close();
			}).dimensions(x, y, buttonWidth, 20).build());
		}
	}

	private void addCoordinateField(int x, int y, int width, String initial, java.util.function.Consumer<String> onChange) {
		TextFieldWidget field = new TextFieldWidget(textRenderer, x, y, width, 18, Text.empty());
		field.setTextPredicate(WaypointEditorScreen::isIntegerText);
		field.setText(initial);
		field.setChangedListener(onChange);
		addDrawableChild(field);
	}

	/** Every dimension the server has told us about, plus the one this waypoint is already in. */
	private List<String> dimensions() {
		TreeSet<String> ids = new TreeSet<>();
		ids.add(dimension);
		if (client != null && client.player != null) {
			for (RegistryKey<World> key : client.player.networkHandler.getWorldKeys()) {
				ids.add(key.getValue().toString());
			}
		}
		return new ArrayList<>(ids);
	}

	private List<String> groupIds() {
		List<String> ids = new ArrayList<>();
		for (WaypointGroup candidate : WaypointStore.groups()) {
			ids.add(candidate.id);
		}
		if (!ids.contains(group)) {
			group = WaypointGroup.DEFAULT_ID;
		}
		return ids;
	}

	private Text dimensionName(String id) {
		return Waypoints.dimensionName(id);
	}

	private void convertTo(String target) {
		xText = Integer.toString(DimensionScale.convert(parse(xText), dimension, target));
		zText = Integer.toString(DimensionScale.convert(parse(zText), dimension, target));
		dimension = target;
		clearAndInit();
	}

	private void useMyPosition() {
		if (client == null || client.player == null) {
			return;
		}
		xText = Integer.toString(client.player.getBlockX());
		yText = Integer.toString(client.player.getBlockY());
		zText = Integer.toString(client.player.getBlockZ());
		// Rebuilding the widgets is the simplest way to show the new numbers.
		clearAndInit();
	}

	private void save() {
		String name = nameText.trim();
		if (name.isEmpty()) {
			name = Text.translatable("waypoint.rtmap.default_name", WaypointStore.waypoints().size() + 1).getString();
		}
		int x = parse(xText);
		int y = parse(yText);
		int z = parse(zText);

		if (existing != null) {
			existing.name = name;
			existing.x = x;
			existing.y = y;
			existing.z = z;
			existing.dimension = dimension;
			existing.group = group;
			existing.color = color;
			existing.enabled = enabled;
			WaypointStore.markChanged();
		} else {
			Waypoint waypoint = Waypoint.create(name, x, y, z, dimension, group, color);
			waypoint.enabled = enabled;
			WaypointStore.add(waypoint);
		}
		close();
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		renderBackground(context);
		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 12, 0xFFFFFF);

		int left = width / 2 - WIDTH / 2;
		int fieldWidth = (WIDTH - 8) / 3;
		context.drawTextWithShadow(textRenderer, Text.translatable("waypoint.rtmap.name"), left, 28, LABEL_COLOR);
		context.drawTextWithShadow(textRenderer, Text.literal("X"), left, 62, LABEL_COLOR);
		context.drawTextWithShadow(textRenderer, Text.literal("Y"), left + fieldWidth + 4, 62, LABEL_COLOR);
		context.drawTextWithShadow(textRenderer, Text.literal("Z"), left + 2 * (fieldWidth + 4), 62, LABEL_COLOR);
		super.render(context, mouseX, mouseY, delta);
	}

	@Override
	public void close() {
		client.setScreen(parent);
	}
}
