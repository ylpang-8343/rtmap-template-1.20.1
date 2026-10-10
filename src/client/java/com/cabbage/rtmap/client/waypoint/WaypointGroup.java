package com.cabbage.rtmap.client.waypoint;

import net.minecraft.text.Text;

/** A named set of waypoints that can be shown or hidden together. */
public final class WaypointGroup {
	public static final String DEFAULT_ID = "default";
	public static final String DEATH_ID = "death";

	public final String id;
	public final boolean builtin;
	public String name;
	public boolean enabled = true;

	public WaypointGroup(String id, String name, boolean builtin) {
		this.id = id;
		this.name = name;
		this.builtin = builtin;
	}

	/** Built-in groups are translated; custom ones show the name the player typed. */
	public Text displayName() {
		return builtin ? Text.translatable("waypoint.rtmap.group." + id) : Text.literal(name);
	}
}
