package dev.spog.tiers.util;

import dev.spog.tiers.SpogTiersClient;
import dev.spog.tiers.client.GlyphIcons;
import dev.spog.tiers.client.ModeIcons;
import dev.spog.tiers.config.SpogTiersConfig;
import dev.spog.tiers.config.TagLayout;
import dev.spog.tiers.data.Gamemode;
import dev.spog.tiers.data.PlayerGrade;
import dev.spog.tiers.data.PlayerTiers;
import dev.spog.tiers.data.Regions;
import dev.spog.tiers.data.Tier;
import dev.spog.tiers.data.TierList;
import net.minecraft.util.Formatting;
import net.minecraft.text.Text;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Builds the tier tags shown around a player's name.
 *
 * <p>The layout is user-configurable: a tag on either side, each bound to a
 * list and optionally a gamemode, with an optional region prefix. Every method
 * is null-safe and returns the original text unchanged when nothing is known,
 * so callers can apply it unconditionally.
 */
public final class TagRenderer {
	private TagRenderer() {
	}

	/**
	 * Returns {@code original} decorated per the user's nametag settings.
	 *
	 * <p>The middle row only. Used for the in-world nametag, which draws its
	 * other two rows itself and so must be left exactly as laid out.
	 */
	public static Text withTag(UUID uuid, Text original) {
		SpogTiersConfig config = SpogTiersClient.config();
		if (config == null || !config.enabled) {
			return original;
		}
		Text middle = buildRow(uuid, TagLayout.Row.MIDDLE, original);
		return middle == null ? original : middle;
	}

	/**
	 * The same, for somewhere with only one line to draw on.
	 *
	 * <p>The tab list gets a single component and has nowhere to put the top
	 * and bottom rows, so Auto Adjust Lines deals them either side of the name
	 * here. The in-world tag calls {@link #withTag} instead: it draws all three
	 * rows, so moving them would undo the arrangement rather than rescue it.
	 */
	public static Text withTagOnOneLine(UUID uuid, Text original) {
		SpogTiersConfig config = SpogTiersClient.config();
		if (config == null || !config.enabled) {
			return original;
		}
		if (config.tagLayout.autoAdjustLines) {
			Text balanced = balancedRow(uuid, original);
			if (balanced != null) {
				return balanced;
			}
		}
		return withTag(uuid, original);
	}

	/**
	 * The middle row dealt either side of the name, for one-line contexts.
	 *
	 * <p>Chat and the tab list have a single line, so a tag built as three
	 * rows arrives with everything bunched on one side of the name. The
	 * elements are split around it instead.
	 *
	 * <p>Two tiers held apart by a separator become one tier either side and
	 * the separator is dropped: it was there to divide them, and once they are
	 * on opposite sides of the name it divides nothing. Without a separator the
	 * sides are simply balanced, the odd one out going left.
	 *
	 * <p>Returns null when there is nothing to rearrange, so the caller falls
	 * back to the row as laid out.
	 */
	private static Text balancedRow(UUID uuid, Text name) {
		Text[] sides = balancedSides(uuid);
		if (sides == null) {
			return null;
		}
		MutableText out = Text.empty();
		if (sides[0] != null) {
			out.append(sides[0]);
		}
		out.append(name);
		if (sides[1] != null) {
			out.append(sides[1]);
		}
		return out;
	}

	/**
	 * The elements of every row, dealt either side of the name.
	 *
	 * <p>Returns what goes before the name and what goes after it, either of
	 * which may be null; null altogether when there is nothing to deal.
	 */
	private static Text[] balancedSides(UUID uuid) {
		SpogTiersConfig config = SpogTiersClient.config();
		// Every row, not just the middle one. Chat and the tab list have a
		// single line, so the top and bottom rows are the ones with nowhere
		// to go -- and the middle row usually holds nothing but the name, so
		// reading only that found nothing to move and did nothing at all.
		//
		// Top first, as the row a person reads first, then the middle, then
		// the bottom.
		List<TagLayout.Element> row = new ArrayList<>();
		boolean hasName = false;
		for (TagLayout.Row which : TagLayout.Row.values()) {
			for (TagLayout.Element element : config.tagLayout.elements) {
				if (element.row != which) {
					continue;
				}
				if (element.kind == TagLayout.Kind.NAME) {
					hasName = true;
					continue;
				}
				row.add(element);
			}
		}
		// Nothing to deal, or nowhere to deal it around. One element is still
		// worth moving: it goes to the left of the name rather than after
		// everything, which is the whole point in a one-line context.
		if (!hasName || row.isEmpty()) {
			return null;
		}

		// A separator on either end divides nothing, so it is dropped rather
		// than dealt to a side of its own -- balancing [sep, tier] would
		// otherwise put a bare separator to the left of the name.
		while (!row.isEmpty() && row.get(0).kind == TagLayout.Kind.SEPARATOR) {
			row.remove(0);
		}
		while (!row.isEmpty()
				&& row.get(row.size() - 1).kind == TagLayout.Kind.SEPARATOR) {
			row.remove(row.size() - 1);
		}
		if (row.isEmpty()) {
			return null;
		}

		// A separator between two others is what it means to be "separated".
		int divider = -1;
		for (int i = 1; i < row.size() - 1; i++) {
			if (row.get(i).kind == TagLayout.Kind.SEPARATOR) {
				divider = i;
				break;
			}
		}

		List<TagLayout.Element> before = new ArrayList<>();
		List<TagLayout.Element> after = new ArrayList<>();
		if (divider >= 0) {
			before.addAll(row.subList(0, divider));
			after.addAll(row.subList(divider + 1, row.size()));
		} else {
			// The odd one out goes left, so two elements split one and one and
			// three split two and one.
			int left = row.size() - row.size() / 2;
			before.addAll(row.subList(0, left));
			after.addAll(row.subList(left, row.size()));
		}

		// The tiers are settled for the whole tag once, the same way the
		// nametag settles them, so chat never quietly differs from the tag
		// above the player's head about which duplicate gave way.
		Map<TagLayout.Element, Resolved> tiers = resolveTiers(uuid, config.tagLayout);
		Text head = sideOf(uuid, before, tiers, true);
		Text tail = sideOf(uuid, after, tiers, false);
		if (head == null && tail == null) {
			return null;
		}
		return new Text[] {head, tail};
	}

	/**
	 * The balanced halves, for a caller that inserts them itself.
	 *
	 * <p>Chat gets a rendered line rather than a name it can wrap, so it finds
	 * the sender's name inside the text and puts these either side of it. The
	 * two entries are what goes before the name and what goes after, either of
	 * which may be null.
	 *
	 * <p>Null when there is nothing to deal, so a caller can fall back to its
	 * own single-badge behaviour.
	 */
	public static Text[] balancedAround(UUID uuid) {
		SpogTiersConfig config = SpogTiersClient.config();
		if (config == null || !config.enabled || !config.tagLayout.autoAdjustLines) {
			return null;
		}
		return balancedSides(uuid);
	}

	/**
	 * One side of a balanced row, or null when none of it resolved.
	 *
	 * <p>Built through the same element handling the row itself uses, so a
	 * tier that resolves to nothing leaves no stray separator or gap behind,
	 * and duplicate suppression still applies across both sides.
	 */
	private static Text sideOf(UUID uuid, List<TagLayout.Element> side,
			Map<TagLayout.Element, Resolved> tiers, boolean leading) {
		List<Text> pieces = resolve(uuid, side, null, tiers);
		if (pieces.isEmpty()) {
			return null;
		}
		MutableText out = Text.empty();
		// A gap against the name on whichever side it sits, so a tier never
		// runs straight into it.
		if (!leading) {
			out.append(space());
		}
		for (int i = 0; i < pieces.size(); i++) {
			out.append(pieces.get(i));
		}
		if (leading) {
			out.append(space());
		}
		return out;
	}

	/**
	 * The middle row split at the name: what precedes it, and what follows.
	 *
	 * <p>Built the same way the row itself is, so the two agree about which
	 * elements resolved and where the separators fell.
	 */
	public static Text[] aroundName(UUID uuid, Text name) {
		SpogTiersConfig config = SpogTiersClient.config();
		if (config == null || !config.enabled || !config.tagLayout.centerOnName) {
			return null;
		}
		return splitRow(uuid, name);
	}

	/** The badge for the first tier element, for compact contexts. */
	public static Text badgeFor(UUID uuid) {
		SpogTiersConfig config = SpogTiersClient.config();
		if (config == null || !config.enabled) {
			return null;
		}
		// No exclusion: this returns the first element that resolves, so
		// there is never an earlier tier for it to be a duplicate of.
		for (TagLayout.Element element : config.tagLayout.elements) {
			if (element.kind != TagLayout.Kind.TIER) {
				continue;
			}
			Resolved tier = resolveTier(uuid, element, Set.of());
			if (tier != null) {
				return tier.text();
			}
		}
		return null;
	}

	/**
	 * The line above the name, or null when nothing is on that row.
	 *
	 * <p>Its own component rather than a line inside the name: vanilla draws
	 * this from a separate field with its own background, so a newline in the
	 * name is ignored on a real nameplate and stretches one wide background
	 * across both lines on a text display.
	 */
	public static Text aboveTag(UUID uuid) {
		SpogTiersConfig config = SpogTiersClient.config();
		if (config == null || !config.enabled) {
			return null;
		}
		return buildRow(uuid, TagLayout.Row.TOP, null);
	}

	/** The line below the name, or null when nothing is on that row. */
	public static Text belowTag(UUID uuid) {
		SpogTiersConfig config = SpogTiersClient.config();
		if (config == null || !config.enabled) {
			return null;
		}
		return buildRow(uuid, TagLayout.Row.BOTTOM, null);
	}

	/**
	 * Builds one row of the tag.
	 *
	 * <p>All three rows come through here, so they cannot disagree about what
	 * an element means or how duplicates are avoided. The tiers come from
	 * {@link #resolveTiers}, which settles the whole tag at once, so Prevent
	 * Duplicates works across every row rather than only within one.
	 *
	 * @param name what a name element draws, or null to skip name elements
	 * @return the row, or null if it came out empty
	 */
	/** The middle row as its two halves either side of the name. */
	private static Text[] splitRow(UUID uuid, Text name) {
		Text whole = buildRow(uuid, TagLayout.Row.MIDDLE, name);
		if (whole == null) {
			return null;
		}
		// Rebuilt rather than searched: the row is a tree of components and
		// finding the name inside it would mean matching on text, which a
		// player called after a tier label would break.
		MutableText before = Text.empty();
		MutableText after = Text.empty();
		boolean seenName = false;
		for (Text piece : rowPieces(uuid, TagLayout.Row.MIDDLE, name)) {
			if (piece == name) {
				seenName = true;
				continue;
			}
			(seenName ? after : before).append(piece);
		}
		return new Text[] {before, after};
	}

	private static Text buildRow(UUID uuid, TagLayout.Row row, Text name) {
		List<Text> pieces = rowPieces(uuid, row, name);
		if (pieces.isEmpty()) {
			return null;
		}
		MutableText out = Text.empty();
		for (Text piece : pieces) {
			out.append(piece);
		}
		return out;
	}

	/**
	 * One row as the pieces it is made of, in order.
	 *
	 * <p>The name is returned as the very object passed in, so a caller can
	 * find it by identity and split the row there.
	 */
	private static List<Text> rowPieces(UUID uuid, TagLayout.Row row, Text name) {
		TagLayout layout = SpogTiersClient.config().tagLayout;
		List<TagLayout.Element> wanted = new ArrayList<>();
		for (TagLayout.Element element : layout.elements) {
			if (element.row == row) {
				wanted.add(element);
			}
		}
		return resolve(uuid, wanted, name, resolveTiers(uuid, layout));
	}

	/** Turns a list of elements into the pieces that get drawn. */
	private static List<Text> resolve(UUID uuid, List<TagLayout.Element> elements,
			Text name, Map<TagLayout.Element, Resolved> tiers) {
		List<Text> pieces = new ArrayList<>();
		// A separator is only worth drawing between two things, so it is held
		// back until something after it earns it. This is what lets a tier
		// resolve to nothing without leaving a stray bar behind.
		Text pending = null;

		for (TagLayout.Element element : elements) {
			Text piece = switch (element.kind) {
				case NAME -> name;
				case REGION -> regionFor(uuid);
				case SEPARATOR -> null;
				case TIER -> {
					Resolved tier = tiers.get(element);
					yield tier == null ? null : tier.text();
				}
			};

			if (element.kind == TagLayout.Kind.SEPARATOR) {
				// Only after something, and only one at a time: two separators
				// in a row with nothing between them draw as one.
				if (!pieces.isEmpty()) {
					pending = separator(element);
				}
				continue;
			}
			if (piece == null) {
				continue;
			}
			if (pending != null) {
				pieces.add(pending);
				pending = null;
			} else if (!pieces.isEmpty()) {
				// Two elements with nothing between them still need holding
				// apart, or a tier runs straight into the name.
				pieces.add(space());
			}
			pieces.add(piece);
		}
		return pieces;
	}

	/**
	 * What every tier element of a layout draws, settled for the whole tag at
	 * once.
	 *
	 * <p>With Prevent Duplicates on, the most specific elements choose first:
	 * those fixed to both a list and a gamemode, then those fixed to one of
	 * the two, then Best elements, each group in layout order. A fixed element
	 * has few places to look while a Best one can always look further, so
	 * handing tiers out in drawing order let a Best element early in the tag
	 * take the very tier a list element further along was pinned to. The
	 * pinned one was then left with nothing, and the tag drew one tier where
	 * it had room for two.
	 *
	 * <p>The whole tag, not one row: each row is built by its own mixin at its
	 * own time, and they have to agree about who gave way.
	 *
	 * <p>Public so the editor's preview draws exactly what the tag will.
	 *
	 * @return each element that resolved, by identity; an element missing
	 *     from the map draws nothing
	 */
	public static Map<TagLayout.Element, Resolved> resolveTiers(UUID uuid, TagLayout layout) {
		Map<TagLayout.Element, Resolved> out = new IdentityHashMap<>();
		if (uuid == null || layout == null) {
			return out;
		}
		boolean dedupe = SpogTiersClient.config().preventDuplicateTiers;
		Set<String> shown = new LinkedHashSet<>();
		for (int fixed = 2; fixed >= 0; fixed--) {
			for (TagLayout.Element element : layout.elements) {
				if (element.kind != TagLayout.Kind.TIER || fixedness(element) != fixed) {
					continue;
				}
				Resolved tier = resolveTier(uuid, element, dedupe ? shown : Set.of());
				if (tier == null) {
					continue;
				}
				out.put(element, tier);
				// Our own list is not part of the six, so it never counts as
				// a duplicate of one of them.
				if (!element.doorSmp) {
					shown.add(duplicateKey(tier.mode(), tier.label()));
				}
			}
		}
		return out;
	}

	/** How many of list and gamemode an element pins: 0 for Best, 2 for both. */
	private static int fixedness(TagLayout.Element element) {
		if (element.doorSmp) {
			return 2;
		}
		return (element.list() != null ? 1 : 0) + (element.gamemode != null ? 1 : 0);
	}

	/**
	 * What makes two tiers the same for Prevent Duplicates: the same tier in
	 * the same gamemode.
	 *
	 * <p>The label alone used to be enough, which made Spear LT3 and Speed LT3
	 * duplicates of each other -- different modes, different achievements --
	 * and only one of them was drawn.
	 */
	private static String duplicateKey(Gamemode mode, String label) {
		return (mode == null ? "" : mode.key()) + ":" + label;
	}

	/**
	 * One tier element, or null when it has nothing to show.
	 *
	 * <p>Our own tierlist stands in for a Diamond SMP tier, which is the mode
	 * it is played at: the two say the same thing and ours is the more
	 * specific. A door tier also fills a tier element that resolved to
	 * nothing, so a graded player shows their grade even when no other list
	 * ranks them.
	 */
	/**
	 * One tier element, or null when it resolves to nothing.
	 *
	 * @param exclude {@link #duplicateKey duplicate keys} already drawn; empty
	 *     when Prevent Duplicates is off and a repeat is allowed to stand
	 */
	private static Resolved resolveTier(UUID uuid, TagLayout.Element element,
			Set<String> exclude) {
		// Our own list is asked for by name now, rather than standing in for a
		// Diamond SMP tier whenever one happened to appear. That guess was
		// wrong as often as it was right, and an element that says Door SMP
		// says what it means.
		if (element.doorSmp) {
			Text door = doorTag(uuid);
			if (door == null) {
				return null;
			}
			PlayerGrade grade = SpogTiersClient.service().grade(uuid);
			return new Resolved(door, grade == null ? "" : grade.label(), Gamemode.DIA_SMP,
					null, null);
		}

		SpogTiersConfig.TagSlot slot =
				new SpogTiersConfig.TagSlot(true, element.list(), element.gamemode);
		// Every element honours the exclusion, pinned or not.
		//
		// A Best element answers it by looking further -- it has other lists
		// or other modes to fall back on -- while a pinned one has a single
		// answer and so drops out instead. That used to be the argument for
		// exempting pinned elements altogether: suppressing one leaves a gap
		// rather than a different tier.
		//
		// But a gap is the point. The exclusion only holds labels already
		// drawn, so the element being dropped is the second copy of something
		// on screen, not the only sighting of it: two elements pinned to the
		// same gamemode on lists that agree -- Diamond SMP on PVPHQ and on
		// SubTiers, both HT2 -- drew HT2 twice with Prevent Duplicates on,
		// which is the whole thing it is meant to stop.
		return resolve(uuid, slot, exclude);
	}


	private static boolean isDiaSmp(Resolved slot) {
		return slot != null && slot.mode() == Gamemode.DIA_SMP;
	}

	/**
	 * Our own tierlist as a nametag tag: the door, then the tier.
	 *
	 * <p>Null unless the player is on it and Door SMP is switched on, so this
	 * only ever displaces another tag when there is something to put there.
	 */
	private static Text doorTag(UUID uuid) {
		SpogTiersConfig config = SpogTiersClient.config();
		if (config == null || !config.extraTierlists) {
			return null;
		}
		PlayerGrade grade = SpogTiersClient.service().grade(uuid);
		if (grade == null || !grade.isGraded()) {
			return null;
		}
		MutableText out = Text.empty();
		if (config.showTagIcons) {
			// No gap of its own: the glyph was shifted right inside its cell,
			// which already widened its advance, so anything added after it
			// read as a space before the tier that no other icon has.
			out.append(ModeIcons.doorRaised());
		}
		out.append(Text.literal(grade.label())
				.setStyle(Style.EMPTY.withColor(grade.foreground() & 0xFFFFFF)));
		return out;
	}

	/**
	 * The above-name tag for whoever a display's text names, or null.
	 *
	 * <p>Resolved from the display's text the same way {@link #taggedDisplay}
	 * finds its player, so the two always agree about whose display it is.
	 */
	public static Text displayAboveTag(Text text) {
		UUID player = displayOwner(text);
		return player == null ? null : aboveTag(player);
	}

	/**
	 * The same decoration, for a nametag drawn as a text display.
	 *
	 * <p>A display carries no link to the player it labels, so the player is
	 * found by matching the display's plain text against the tab list. That is
	 * a guess, but a safe one: a display whose text contains nobody's name is
	 * left alone, which is the common case for holograms and signs.
	 *
	 * @return the decorated text, or null to leave the display untouched
	 */
	public static Text taggedDisplay(Text text) {
		UUID player = displayOwner(text);
		if (player == null) {
			return null;
		}
		Text tagged = withTag(player, text);
		return tagged == text ? null : tagged;
	}

	/**
	 * Whose nametag a text display is, or null if it is not one.
	 *
	 * <p>A display carries no link to the player it labels, so the player is
	 * found by matching its plain text against the tab list. That is a guess,
	 * but a safe one: a display whose text contains nobody's name is left
	 * alone, which is the common case for holograms and signs.
	 *
	 * <p>Longest name wins, so "Spog" cannot claim a display belonging to
	 * "Spoginator".
	 */
	private static UUID displayOwner(Text text) {
		SpogTiersConfig config = SpogTiersClient.config();
		if (config == null || !config.enabled) {
			return null;
		}
		var client = net.minecraft.client.MinecraftClient.getInstance();
		if (client.getNetworkHandler() == null) {
			return null;
		}
		String plain = text.getString();
		if (plain.isBlank()) {
			return null;
		}

		UUID best = null;
		int bestLength = 0;
		for (var info : client.getNetworkHandler().getPlayerList()) {
			String name = info.getProfile().name();
			if (name == null || name.length() <= bestLength || !plain.contains(name)) {
				continue;
			}
			best = info.getProfile().id();
			bestLength = name.length();
		}
		return best;
	}

	/**
	 * What a tier element settled on: the drawn text, the tier label behind
	 * it, and the gamemode and list it was found at.
	 *
	 * <p>{@code tier} and {@code list} are null for our own list, which is a
	 * grade rather than a tier.
	 */
	public record Resolved(Text text, String label, Gamemode mode, Tier tier,
			TierList list) {
	}

	/**
	 * One configured slot, or null when it is off or nothing is ranked.
	 *
	 * <p>Searches every place the slot allows -- one list or every tagged
	 * list, one gamemode or every gamemode -- for the best tier not already
	 * drawn. A slot pinned to both a list and a mode has one place to look,
	 * so if that tier is excluded it shows nothing; anything looser falls back
	 * to its next best tier instead.
	 *
	 * <p>A Best slot used to compare only each list's single top tier, so
	 * when that one was excluded the whole list dropped out and the slot drew
	 * nothing, though the player held other tiers on it.
	 *
	 * @param exclude {@link #duplicateKey duplicate keys} the slot must not
	 *     repeat; empty for no restriction
	 */
	private static Resolved resolve(UUID uuid, SpogTiersConfig.TagSlot slot, Set<String> exclude) {
		SpogTiersConfig config = SpogTiersClient.config();
		if (slot == null || !slot.enabled) {
			return null;
		}

		TierList bestList = null;
		Gamemode bestMode = null;
		Tier best = null;
		for (Map.Entry<TierList, PlayerTiers> entry
				: SpogTiersClient.cache().allLists(uuid).entrySet()) {
			TierList list = entry.getKey();
			PlayerTiers tiers = entry.getValue();
			// A null list is the Best option: every list is a candidate.
			if (slot.list != null && list != slot.list) {
				continue;
			}
			// A list can be shown in results but kept off tags, so it is never
			// quoted on a nametag, picked explicitly or not.
			if (tiers == null || !config.isEnabled(list) || !config.isTagged(list)) {
				continue;
			}
			for (Map.Entry<Gamemode, Tier> ranked : tiers.all().entrySet()) {
				Gamemode mode = ranked.getKey();
				Tier tier = ranked.getValue();
				if (slot.gamemode != null && mode != slot.gamemode) {
					continue;
				}
				if (tier == null || !tier.isRanked()
						|| exclude.contains(duplicateKey(mode, tier.label()))) {
					continue;
				}
				if (best == null || outranks(tier, best)) {
					bestList = list;
					bestMode = mode;
					best = tier;
				}
			}
		}
		if (best == null) {
			return null;
		}

		MutableText out = Text.empty();
		if (config.showTagIcons) {
			Text icon = iconFor(bestList, bestMode);
			if (icon != null) {
				out.append(icon).append(glyphGap());
			}
		}
		appendTier(out, best);
		return new Resolved(out, best.label(), bestMode, best, bestList);
	}

	/** Lunar Client's own retired colour, from its tiertagger config. */
	private static final int LUNAR_RETIRED = 0xFFA2D6FF;

	/**
	 * The tier's label, with retirement marked whichever way is configured.
	 *
	 * <p>Normally the R is part of the label and takes the tier's colour, so a
	 * retired HT1 is one run reading {@code RHT1}. Lunar's form is a bracketed
	 * marker in its own light blue ahead of the tier, which keeps the tier's
	 * colour readable next to it.
	 */
	private static void appendTier(MutableText out, Tier tier) {
		SpogTiersConfig config = SpogTiersClient.config();
		if (config.lunarRetired && tier.showsRetirement()) {
			// bareLabel drops the R the normal form fuses on, so the marker is
			// not written twice.
			out.append(Text.literal("(R)")
					.setStyle(Style.EMPTY.withColor(LUNAR_RETIRED)));
			out.append(space());
			out.append(Text.literal(tier.bareLabel())
					.setStyle(Style.EMPTY.withColor(tier.color())));
			return;
		}
		out.append(Text.literal(tier.label())
				.setStyle(Style.EMPTY.withColor(tier.color())));
	}

	/** True when {@code candidate} is the better of the two tiers. */
	private static boolean outranks(Tier candidate, Tier current) {
		if (candidate.tier() != current.tier()) {
			return candidate.tier() < current.tier();
		}
		return candidate.position().ordinal() < current.position().ordinal();
	}

	/** The icon for a tier found at {@code mode} on {@code list}. */
	private static Text iconFor(TierList list, Gamemode mode) {
		// A glyph style first, and only when it has one for this mode: the
		// modes only CatPVP and MCPvP rank have no Lunar equivalent, and they
		// keep their own list's artwork rather than losing an icon.
		Text glyph = GlyphIcons.of(SpogTiersClient.config().iconStyle, mode);
		return glyph != null ? glyph : ModeIcons.of(list, mode.key());
	}

	/**
	 * The player's region as a small coloured prefix.
	 *
	 * <p>Resolved the same way the profile screen does it, so the nametag and
	 * the panel never disagree about where someone is from.
	 */
	private static Text regionFor(UUID uuid) {
		String code = Regions.resolve(SpogTiersClient.cache().allLists(uuid));
		if (code.isEmpty()) {
			return null;
		}
		return Text.literal(code)
				.setStyle(Style.EMPTY.withColor(regionColor(code)));
	}

	private static int regionColor(String region) {
		return switch (region) {
			case "NA" -> 0xD95C6A;
			case "EU" -> 0x89F19C;
			case "AS" -> 0xAF7F91;
			case "AU", "OCE", "OC" -> 0xD5AD80;
			case "SA" -> 0x5DCCDC;
			case "ME" -> 0xE0B36A;
			case "AF" -> 0x9FD18A;
			default -> 0xB9C4D0;
		};
	}

	/**
	 * What an element draws between two parts of a row.
	 *
	 * <p>Spaced on both sides rather than butted against its neighbours: the
	 * backdrop already extends a couple of pixels past the text, and without
	 * the padding a bar sits directly against the name.
	 */
	private static Text separator(TagLayout.Element element) {
		return Text.literal(" " + element.character + " ")
				.setStyle(Style.EMPTY.withColor(element.colour));
	}


	private static Text space() {
		return Text.literal(" ");
	}

	/**
	 * The gap after a bitmap glyph.
	 *
	 * <p>One space. These glyphs are eight to ten pixels tall in a font built
	 * for seven, so they render wider than their advance suggests and butt
	 * against the next character with no gap at all -- but two spaces pushed
	 * the tier noticeably away from its own icon, so a single one it is.
	 */
	private static Text glyphGap() {
		return Text.literal(" ");
	}
}
