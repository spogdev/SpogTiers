package dev.spog.tiers.config;

import dev.spog.tiers.data.Gamemode;
import dev.spog.tiers.data.TierList;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A shareable code carrying one player's tag layout and the settings that
 * shape it.
 *
 * <p>Plain readable text rather than anything packed or encoded. The data is
 * nobody's secret -- it is a nametag someone wants to hand out -- so the only
 * thing worth optimising is how short and how durable it is.
 *
 * <p>The point is that a code outlives the version that wrote it. Someone on
 * an older build has to be able to read a code from a newer one, and the other
 * way round, for as long as the two still mean the same thing by a nametag.
 * Four rules keep that true:
 *
 * <ul>
 *   <li><b>Everything is named, nothing is positional.</b> Settings are
 *       {@code key=value} and elements name their kind, so a field added in
 *       the middle cannot shift the meaning of the ones after it.
 *   <li><b>Enum values travel as their own names.</b> Inserting a value into
 *       {@link TierList} or {@link Gamemode} cannot silently turn someone's
 *       PvPTiers element into a SubTiers one.
 *   <li><b>Unknown parts are skipped, missing parts keep this build's
 *       default.</b> A newer build's extra setting costs an older one nothing,
 *       and a setting added since a code was written is simply left alone.
 *   <li><b>One number gates the whole thing.</b> {@link #FORMAT} rises only if
 *       codes stop being readable at all -- never for adding a field, which
 *       the rules above already cover.
 * </ul>
 *
 * <p>A code reads roughly:
 * {@snippet : SPOG1 nm tb ch dl=PVPHQ T.T.best N.M R.M}
 *
 * <p>What is deliberately not in a code: cache and request tuning, which is
 * about someone's connection rather than their setup, and anything saying who
 * exported it.
 */
public final class LayoutCode {
	/**
	 * How a code is shaped, not what it contains.
	 *
	 * <p>Raised only for a change that leaves an old reader unable to make
	 * sense of a code at all.
	 */
	private static final int FORMAT = 1;

	/** Marks a string as one of ours, and says which format follows. */
	private static final String PREFIX = "SPOG";

	/**
	 * The on/off settings, by the short name each travels under.
	 *
	 * <p>Two letters rather than the field's own name, which is most of what
	 * made codes long. The mapping is fixed: a name here is never reused for a
	 * different setting, so an old code keeps meaning what it meant.
	 */
	private static final Map<String, Switch> SWITCHES = new LinkedHashMap<>();

	/** One boolean setting, read and written by short name. */
	private record Switch(java.util.function.Predicate<SpogTiersConfig> get,
			java.util.function.BiConsumer<SpogTiersConfig, Boolean> set) {
	}

	static {
		SWITCHES.put("nm", new Switch(c -> c.showNametags, (c, v) -> c.showNametags = v));
		SWITCHES.put("tb", new Switch(c -> c.showTabList, (c, v) -> c.showTabList = v));
		SWITCHES.put("ch", new Switch(c -> c.showInChat, (c, v) -> c.showInChat = v));
		SWITCHES.put("td", new Switch(c -> c.tagDisplays, (c, v) -> c.tagDisplays = v));
		SWITCHES.put("bt", new Switch(c -> c.showBestTier, (c, v) -> c.showBestTier = v));
		SWITCHES.put("rt", new Switch(c -> c.showRetired, (c, v) -> c.showRetired = v));
		SWITCHES.put("pl", new Switch(c -> c.showPlacements, (c, v) -> c.showPlacements = v));
		SWITCHES.put("pa", new Switch(c -> c.showParticles, (c, v) -> c.showParticles = v));
		SWITCHES.put("sp", new Switch(c -> c.showSeparators, (c, v) -> c.showSeparators = v));
		SWITCHES.put("pd", new Switch(c -> c.preventDuplicateTiers,
				(c, v) -> c.preventDuplicateTiers = v));
		SWITCHES.put("xt", new Switch(c -> c.extraTierlists, (c, v) -> c.extraTierlists = v));
		SWITCHES.put("cn", new Switch(c -> c.tagLayout != null && c.tagLayout.centerOnName,
				(c, v) -> c.tagLayout.centerOnName = v));
		SWITCHES.put("ax", new Switch(c -> c.tagLayout != null && c.tagLayout.autoExpand,
				(c, v) -> c.tagLayout.autoExpand = v));
		SWITCHES.put("al", new Switch(c -> c.tagLayout != null && c.tagLayout.autoAdjustLines,
				(c, v) -> c.tagLayout.autoAdjustLines = v));
	}

	/**
	 * The switches that belong to the layout rather than to the config.
	 *
	 * <p>They are held until the imported layout is the one in the config, so
	 * a code that carries elements does not set them on a layout it is about
	 * to replace.
	 */
	private static final java.util.Set<String> LAYOUT_SWITCHES =
			java.util.Set.of("cn", "ax", "al");

	private LayoutCode() {
	}

	/** What a code failed to be, for a message the user can act on. */
	public enum Problem {
		/** Not one of our codes at all: a stray paste, or cut short. */
		NOT_A_CODE("Invalid code"),
		/** Ours, but written by a build whose format this one cannot read. */
		TOO_NEW("That code is from a newer version of SpogTiers than this one"),
		/** Ours and the right format, but nothing usable in it. */
		DAMAGED("That code is incomplete or damaged");

		private final String message;

		Problem(String message) {
			this.message = message;
		}

		public String message() {
			return message;
		}
	}

	/** Either the parts a code carried, or why it could not be read. */
	public record Result(List<String> parts, Problem problem) {
		public boolean ok() {
			return parts != null;
		}
	}

	/**
	 * Writes the current layout and the settings that shape it as one code.
	 *
	 * <p>Only what differs from a plain switch is written where it can be
	 * left out: an element takes its row and nothing else unless it has more
	 * to say. That is most of why a code is short.
	 */
	public static String write(SpogTiersConfig config) {
		List<String> parts = new ArrayList<>();
		parts.add(PREFIX + FORMAT);

		// Switches that are on travel as a bare name, off as name-with-slash.
		// Nothing is shorter than a word that means itself.
		SWITCHES.forEach((name, setting) ->
				parts.add(setting.get().test(config) ? name : name + "/"));

		parts.add("dl=" + config.displayList.name());
		parts.add("dm=" + config.displayMode.name());
		if (config.regionSlot != null) {
			parts.add("rs=" + config.regionSlot.name());
		}
		parts.add("so=" + config.sortOrder.name());
		lists(parts, "tl", config.taggedLists);
		lists(parts, "el", config.enabledLists);

		if (config.tagLayout != null) {
			for (TagLayout.Element element : config.tagLayout.elements) {
				parts.add(element(element));
			}
		}
		return String.join(" ", parts);
	}

	/**
	 * Reads a code, or says why it could not be.
	 *
	 * <p>Split on any run of whitespace, so a code that came through chat or
	 * an email with a line break in the middle still reads.
	 */
	public static Result read(String code) {
		if (code == null || code.isBlank()) {
			return new Result(null, Problem.NOT_A_CODE);
		}
		String[] words = code.trim().split("\\s+");
		String head = words[0];
		if (!head.startsWith(PREFIX)) {
			return new Result(null, Problem.NOT_A_CODE);
		}
		int format;
		try {
			format = Integer.parseInt(head.substring(PREFIX.length()));
		} catch (NumberFormatException e) {
			return new Result(null, Problem.NOT_A_CODE);
		}
		if (format > FORMAT) {
			return new Result(null, Problem.TOO_NEW);
		}
		if (words.length < 2) {
			return new Result(null, Problem.DAMAGED);
		}
		return new Result(List.of(words).subList(1, words.length), null);
	}

	/**
	 * Applies a code that {@link #read} accepted, and says what was dropped.
	 *
	 * <p>Part by part, skipping anything unrecognised: a code from a newer
	 * build cannot introduce a setting this one does not understand, and one
	 * from an older build leaves everything it never mentioned alone.
	 *
	 * @return a note on what could not be carried across, or null when all of
	 *     it applied
	 */
	public static String apply(List<String> parts, SpogTiersConfig config) {
		List<String> dropped = new ArrayList<>();
		TagLayout layout = new TagLayout();
		boolean sawElement = false;

		for (String part : parts) {
			if (part.isEmpty()) {
				continue;
			}
			int equals = part.indexOf('=');
			if (equals > 0) {
				value(part.substring(0, equals), part.substring(equals + 1), config, dropped);
				continue;
			}
			String name = part.endsWith("/") ? part.substring(0, part.length() - 1) : part;
			Switch setting = SWITCHES.get(name);
			if (setting != null) {
				// These live on the layout being built, so they are held until
				// that layout is the one in the config.
				if (LAYOUT_SWITCHES.contains(name)) {
					boolean on = !part.endsWith("/");
					switch (name) {
						case "cn" -> layout.centerOnName = on;
						case "ax" -> layout.autoExpand = on;
						default -> layout.autoAdjustLines = on;
					}
				} else {
					setting.set().accept(config, !part.endsWith("/"));
				}
				continue;
			}
			TagLayout.Element element = element(part, dropped);
			if (element != null) {
				layout.elements.add(element);
				sawElement = true;
			}
		}

		if (sawElement) {
			config.tagLayout = layout;
		} else if (config.tagLayout != null) {
			config.tagLayout.centerOnName = layout.centerOnName;
			config.tagLayout.autoExpand = layout.autoExpand;
			config.tagLayout.autoAdjustLines = layout.autoAdjustLines;
		}
		config.normalise();
		config.save();
		return dropped.isEmpty() ? null
				: "Imported, without " + String.join(", ", dropped);
	}

	/** One element as text: kind, row, then only what it needs. */
	private static String element(TagLayout.Element element) {
		StringBuilder out = new StringBuilder();
		out.append(switch (element.kind) {
			case NAME -> "N";
			case TIER -> "T";
			case REGION -> "R";
			case SEPARATOR -> "S";
		});
		out.append('.').append(switch (element.row) {
			case TOP -> "T";
			case MIDDLE -> "M";
			case BOTTOM -> "B";
		});
		if (element.kind == TagLayout.Kind.TIER) {
			if (element.doorSmp) {
				out.append(".door");
			} else if (element.best || element.list == null) {
				out.append(".best");
			} else {
				out.append('.').append(element.list.name());
			}
			if (element.gamemode != null) {
				out.append('.').append(element.gamemode.name());
			}
		} else if (element.kind == TagLayout.Kind.SEPARATOR) {
			// The character by codepoint, so a code stays plain ASCII wherever
			// it is pasted, and the colour only when it is not the usual one.
			out.append('.').append(Integer.toHexString(
					element.character == null || element.character.isEmpty()
							? '|' : element.character.codePointAt(0)));
			if (element.colour != 0x555555) {
				out.append('.').append(Integer.toHexString(element.colour & 0xFFFFFF));
			}
		}
		return out.toString();
	}

	/** One element back from text, or null when this build cannot draw it. */
	private static TagLayout.Element element(String part, List<String> dropped) {
		String[] bits = part.split("\\.");
		TagLayout.Kind kind = switch (bits[0]) {
			case "N" -> TagLayout.Kind.NAME;
			case "T" -> TagLayout.Kind.TIER;
			case "R" -> TagLayout.Kind.REGION;
			case "S" -> TagLayout.Kind.SEPARATOR;
			default -> null;
		};
		if (kind == null) {
			// Either a kind this build has never heard of, or a setting from a
			// newer one. Either way the rest of the tag is worth keeping.
			note(dropped, "a part this version does not have");
			return null;
		}
		TagLayout.Element element = new TagLayout.Element();
		element.kind = kind;
		element.row = bits.length > 1 ? switch (bits[1]) {
			case "T" -> TagLayout.Row.TOP;
			case "B" -> TagLayout.Row.BOTTOM;
			default -> TagLayout.Row.MIDDLE;
		} : TagLayout.Row.MIDDLE;

		if (kind == TagLayout.Kind.TIER && bits.length > 2) {
			if (bits[2].equals("door")) {
				element.doorSmp = true;
			} else if (bits[2].equals("best")) {
				element.best = true;
			} else {
				TierList list = constant(TierList.class, bits[2]);
				if (list == null) {
					// A list this build does not have. Best across the ones it
					// does is the closest honest reading of "a tier here".
					note(dropped, "a tier list this version does not have");
					element.best = true;
				} else {
					element.list = list;
				}
			}
			if (bits.length > 3) {
				element.gamemode = constant(Gamemode.class, bits[3]);
			}
		} else if (kind == TagLayout.Kind.TIER) {
			element.best = true;
		} else if (kind == TagLayout.Kind.SEPARATOR) {
			element.character = bits.length > 2 ? character(bits[2]) : "|";
			element.colour = bits.length > 3 ? number(bits[3], 0x555555) : 0x555555;
		}
		return element;
	}

	/** Per-list switches, written only for the lists that are off. */
	private static void lists(List<String> parts, String key,
			Map<TierList, Boolean> from) {
		if (from == null) {
			return;
		}
		List<String> off = new ArrayList<>();
		from.forEach((list, on) -> {
			if (list != null && Boolean.FALSE.equals(on)) {
				off.add(list.name());
			}
		});
		// Only the exceptions: every list is on by default, so a code for the
		// usual case says nothing at all about them.
		if (!off.isEmpty()) {
			parts.add(key + "=" + String.join("+", off));
		}
	}

	/** One {@code key=value} part, ignoring keys this build does not know. */
	private static void value(String key, String value, SpogTiersConfig config,
			List<String> dropped) {
		switch (key) {
			case "dl" -> {
				TierList list = constant(TierList.class, value);
				if (list != null) {
					config.displayList = list;
				} else {
					note(dropped, "a tier list this version does not have");
				}
			}
			case "dm" -> {
				Gamemode mode = constant(Gamemode.class, value);
				if (mode != null) {
					config.displayMode = mode;
				}
			}
			case "rs" -> {
				SpogTiersConfig.RegionSlot slot =
						constant(SpogTiersConfig.RegionSlot.class, value);
				if (slot != null) {
					config.regionSlot = slot;
				}
			}
			case "so" -> {
				SpogTiersConfig.SortOrder order =
						constant(SpogTiersConfig.SortOrder.class, value);
				if (order != null) {
					config.sortOrder = order;
				}
			}
			case "tl" -> lists(value, config.taggedLists);
			case "el" -> lists(value, config.enabledLists);
			default -> {
				// A setting from a newer build. Skipped in silence: saying so
				// for every one of them would bury what actually went wrong.
			}
		}
	}

	/** Turns the lists a code names off, leaving the rest on. */
	private static void lists(String value, Map<TierList, Boolean> into) {
		if (into == null) {
			return;
		}
		for (TierList list : TierList.values()) {
			into.put(list, true);
		}
		for (String name : value.split("\\+")) {
			TierList list = constant(TierList.class, name);
			if (list != null) {
				into.put(list, false);
			}
		}
	}

	private static void note(List<String> dropped, String what) {
		if (!dropped.contains(what)) {
			dropped.add(what);
		}
	}

	private static <E extends Enum<E>> E constant(Class<E> type, String name) {
		try {
			return Enum.valueOf(type, name);
		} catch (IllegalArgumentException | NullPointerException e) {
			return null;
		}
	}

	private static String character(String hex) {
		int codepoint = number(hex, '|');
		return codepoint <= 0 ? "|" : new String(Character.toChars(codepoint));
	}

	private static int number(String hex, int fallback) {
		try {
			return Integer.parseInt(hex, 16);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}
}
