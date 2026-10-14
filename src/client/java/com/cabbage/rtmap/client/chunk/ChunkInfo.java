package com.cabbage.rtmap.client.chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.cabbage.rtmap.chunk.ChunkSnapshot;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** The text shown when the cursor rests on a chunk with chunk data on screen. */
public final class ChunkInfo {
	private ChunkInfo() {
	}

	/** Lines for this chunk, or an empty list if the snapshot does not cover it or the chunk is not loaded. */
	public static List<Text> lines(ChunkSnapshot snapshot, int chunkX, int chunkZ) {
		List<Text> lines = new ArrayList<>();
		if (snapshot == null || !snapshot.contains(chunkX, chunkZ)) {
			return lines;
		}

		lines.add(Text.translatable("chunk.rtmap.title", chunkX, chunkZ).formatted(Formatting.WHITE));
		if (snapshot.hasState()) {
			int state = snapshot.state(chunkX, chunkZ);
			int level = state & ChunkSnapshot.LEVEL_MASK;
			if (level == ChunkSnapshot.LEVEL_NONE) {
				lines.add(Text.translatable("chunk.rtmap.level.none").formatted(Formatting.GRAY));
				return lines;
			}
			lines.add(Text.translatable("chunk.rtmap.level." + levelName(level)).formatted(Formatting.YELLOW));

			List<Text> held = new ArrayList<>();
			addIf(held, state, ChunkSnapshot.TICKET_FORCED, "forced");
			addIf(held, state, ChunkSnapshot.TICKET_PORTAL, "portal");
			addIf(held, state, ChunkSnapshot.TICKET_PLAYER, "player");
			addIf(held, state, ChunkSnapshot.TICKET_START, "start");
			addIf(held, state, ChunkSnapshot.TICKET_DRAGON, "dragon");
			addIf(held, state, ChunkSnapshot.TICKET_OTHER, "other");
			if (held.isEmpty()) {
				lines.add(Text.translatable("chunk.rtmap.no_ticket").formatted(Formatting.GRAY));
			} else {
				Text joined = held.get(0);
				for (int i = 1; i < held.size(); i++) {
					joined = Text.empty().append(joined).append(", ").append(held.get(i));
				}
				lines.add(Text.translatable("chunk.rtmap.tickets", joined).formatted(Formatting.GRAY));
			}
		}
		if (snapshot.hasLoad()) {
			lines.add(Text.translatable("chunk.rtmap.counts", snapshot.entities(chunkX, chunkZ),
				snapshot.blockEntities(chunkX, chunkZ), snapshot.hoppers(chunkX, chunkZ)));
			if (snapshot.hasState()) {
				lines.add(Text.translatable("chunk.rtmap.score",
					String.format(Locale.ROOT, "%.1f", ChunkLoadScore.of(snapshot, chunkX, chunkZ))).formatted(Formatting.GRAY));
			}
		}
		return lines;
	}

	private static String levelName(int level) {
		return switch (level) {
			case ChunkSnapshot.LEVEL_ENTITY_TICKING -> "entity_ticking";
			case ChunkSnapshot.LEVEL_BLOCK_TICKING -> "block_ticking";
			default -> "border";
		};
	}

	private static void addIf(List<Text> out, int state, int bit, String name) {
		if ((state & bit) != 0) {
			out.add(Text.translatable("chunk.rtmap.ticket." + name));
		}
	}
}
