package com.cabbage.rtmap.client.chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.cabbage.rtmap.chunk.ChunkSnapshot;
import com.cabbage.rtmap.chunk.ChunkTimingResult;
import com.cabbage.rtmap.client.perf.TimingPanel;
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

	/**
	 * What the last timing run measured for this chunk: its cost per tick and its share of the server's tick time,
	 * then each kind of cost. Empty if the chunk was not among the expensive ones.
	 */
	public static List<Text> timingLines(ChunkTimingResult result, String dimensionId, int chunkX, int chunkZ) {
		List<Text> lines = new ArrayList<>();
		ChunkTimingResult.Dimension dimension = result == null ? null : result.dimension(dimensionId);
		int entry = dimension == null ? -1 : dimension.find(chunkX, chunkZ);
		if (entry < 0) {
			return lines;
		}
		double ticks = Math.max(1, result.ticks());
		long total = dimension.totalMicros(entry);
		String share = result.tickMicros() > 0 ? String.format(Locale.ROOT, "%.1f", 100.0 * total / result.tickMicros()) : "?";
		lines.add(Text.translatable("timing.rtmap.chunk_cost", TimingPanel.format(total / 1000.0 / ticks), share).formatted(Formatting.GOLD));

		List<Integer> order = new ArrayList<>();
		for (int category = 0; category < ChunkTimingResult.CATEGORIES; category++) {
			if (dimension.micros(entry, category) > 0) {
				order.add(category);
			}
		}
		order.sort((a, b) -> Integer.compare(dimension.micros(entry, b), dimension.micros(entry, a)));
		for (int category : order) {
			lines.add(Text.literal("  " + TimingPanel.categoryName(category) + ": "
				+ TimingPanel.format(dimension.micros(entry, category) / 1000.0 / ticks) + " ms").formatted(Formatting.GRAY));
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
