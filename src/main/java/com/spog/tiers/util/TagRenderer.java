package com.spog.tiers.util;

import com.spog.tiers.SpogTiersClient;
import com.spog.tiers.client.ModeIcons;
import com.spog.tiers.config.SpogTiersConfig;
import com.spog.tiers.config.TagLayout;
import com.spog.tiers.data.Gamemode;
import com.spog.tiers.data.PlayerGrade;
import com.spog.tiers.data.PlayerTiers;
import com.spog.tiers.data.Regions;
import com.spog.tiers.data.Tier;
import com.spog.tiers.data.TierList;
import net.minecraft.util.Formatting;
import net.minecraft.text.Text;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;

import java.util.ArrayList;
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

	/** Returns {@code original} decorated per the user's nametag settings. */
	public static Text withTag(UUID uuid, Text original) {
		SpogTiersConfig config = SpogTiersClient.config();
		if (config == null || !config.enabled) {
			return original;
		}
		Text middle = buildRow(uuid, TagLayout.Row.MIDDLE, original);
		return middle == null ? original : middle;
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
		Set<String> shown = new LinkedHashSet<>();
		for (TagLayout.Element element : config.tagLayout.elements) {
			if (element.kind != TagLayout.Kind.TIER) {
				continue;
			}
			Resolved tier = resolveTier(uuid, element, shown);
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
	 * an element means or how duplicates are avoided. Tiers are resolved in
	 * the order they are drawn -- top row, then middle, then bottom -- and
	 * each one is told what the earlier ones settled on, so Prevent Duplicates
	 * works across the whole tag rather than only between two fixed slots.
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
		SpogTiersConfig config = SpogTiersClient.config();
		TagLayout layout = config.tagLayout;
		Set<String> shown = config.preventDuplicateTiers
				? labelsBefore(uuid, row) : new LinkedHashSet<>();

		List<Text> pieces = new ArrayList<>();
		// A separator is only worth drawing between two things, so it is held
		// back until something after it earns it. This is what lets a tier
		// resolve to nothing without leaving a stray bar behind.
		Text pending = null;

		for (TagLayout.Element element : layout.elements) {
			if (element.row != row) {
				continue;
			}
			Text piece = switch (element.kind) {
				case NAME -> name;
				case REGION -> regionFor(uuid);
				case SEPARATOR -> null;
				case TIER -> {
					Resolved tier = resolveTier(uuid, element, shown);
					if (tier == null) {
						yield null;
					}
					shown.add(tier.label());
					yield tier.text();
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
	 * The tier labels drawn before this row, so a later row does not repeat
	 * them.
	 *
	 * <p>Recomputed rather than remembered between calls: each row is built
	 * from its own mixin at its own time, and state passed between them would
	 * go stale the moment the layout or the player's tiers changed.
	 */
	private static Set<String> labelsBefore(UUID uuid, TagLayout.Row row) {
		SpogTiersConfig config = SpogTiersClient.config();
		Set<String> labels = new LinkedHashSet<>();
		for (TagLayout.Element element : config.tagLayout.elements) {
			if (element.kind != TagLayout.Kind.TIER
					|| element.row.ordinal() >= row.ordinal()) {
				continue;
			}
			// Our own list is not part of the six, so it never counts as a
			// duplicate of one of them.
			if (element.doorSmp) {
				continue;
			}
			Resolved tier = resolveTier(uuid, element, labels);
			if (tier != null) {
				labels.add(tier.label());
			}
		}
		return labels;
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
			return new Resolved(door, grade == null ? "" : grade.label(), null);
		}

		SpogTiersConfig.TagSlot slot =
				new SpogTiersConfig.TagSlot(true, element.list(), element.gamemode);
		// Only an element with a Best somewhere in it can honour an exclusion:
		// it has other lists or other modes to fall back on. One pinned to a
		// list and a mode has exactly one answer, and suppressing it would
		// leave a hole rather than a different tier.
		Set<String> applies = element.list() == null || element.gamemode == null
				? exclude : Set.of();
		return resolve(uuid, slot, applies);
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

	/** What a slot settled on: the drawn text, and the tier label behind it. */
	private record Resolved(Text text, String label, Gamemode mode) {
	}

	/**
	 * One configured side, or null when it is off or nothing is ranked.
	 *
	 * @param exclude tier labels the slot must not repeat; empty for no
	 *     restriction. A Best slot searches past them for the next best thing;
	 *     a slot pinned to one list has nowhere else to look and shows nothing.
	 */
	private static Resolved resolve(UUID uuid, SpogTiersConfig.TagSlot slot, Set<String> exclude) {
		SpogTiersConfig config = SpogTiersClient.config();
		if (slot == null || !slot.enabled) {
			return null;
		}

		// A null list is the Best option: search every enabled list rather than
		// one in particular.
		TierList source = slot.list;
		PlayerTiers tiers;
		Tier tier;

		// A list can be shown in results but kept off tags, so an explicitly
		// picked slot is dropped rather than quoting a list the player asked
		// not to see on nametags.
		if (source != null && !config.isTagged(source)) {
			return null;
		}

		if (source == null) {
			Best best = bestAcrossLists(uuid, slot.gamemode, exclude);
			if (best == null) {
				return null;
			}
			source = best.list();
			tiers = best.tiers();
			tier = best.tier();
		} else {
			if (!config.isEnabled(source)) {
				return null;
			}
			tiers = SpogTiersClient.cache().get(uuid, source);
			if (tiers == null) {
				return null;
			}
			tier = slot.gamemode == null ? tiers.best() : tiers.get(slot.gamemode);
		}

		if (tier == null || !tier.isRanked()) {
			return null;
		}
		// A pinned slot has only one answer, so if that is an excluded one it
		// simply has nothing to show.
		if (exclude.contains(tier.label())) {
			return null;
		}

		MutableText out = Text.empty();
		Gamemode shown = slot.gamemode != null ? slot.gamemode : tiers.bestMode();
		if (config.showTagIcons) {
			Text icon = iconFor(source, slot.gamemode, tiers, tier);
			if (icon != null) {
				out.append(icon).append(glyphGap());
			}
		}
		out.append(Text.literal(tier.label())
				.setStyle(Style.EMPTY.withColor(tier.color())));
		return new Resolved(out, tier.label(), shown);
	}

	/**
	 * The single best tier a player holds across every enabled list.
	 *
	 * <p>With no gamemode it considers every mode on every list; with one, only
	 * that mode, on the lists that rank it. The owning list comes back too, so
	 * the caller can draw that list's own artwork for the mode.
	 */
	private static Best bestAcrossLists(UUID uuid, Gamemode mode, Set<String> exclude) {
		SpogTiersConfig config = SpogTiersClient.config();
		Best best = null;

		for (Map.Entry<TierList, PlayerTiers> entry
				: SpogTiersClient.cache().allLists(uuid).entrySet()) {
			TierList list = entry.getKey();
			PlayerTiers tiers = entry.getValue();
			if (tiers == null || !config.isEnabled(list)) {
				continue;
			}
			if (!config.isTagged(list)) {
				continue;
			}
			// A list that does not rank the chosen mode has no say here.
			if (mode != null && !list.gamemodes().contains(mode)) {
				continue;
			}

			Tier candidate = mode == null ? tiers.best() : tiers.get(mode);
			if (candidate == null || !candidate.isRanked()) {
				continue;
			}
			// Skipped rather than returned, so the search carries on to the
			// next best thing instead of giving up on the slot entirely.
			if (exclude.contains(candidate.label())) {
				continue;
			}
			if (best == null || outranks(candidate, best.tier())) {
				best = new Best(list, tiers, candidate);
			}
		}
		return best;
	}

	/** True when {@code candidate} is the better of the two tiers. */
	private static boolean outranks(Tier candidate, Tier current) {
		if (candidate.tier() != current.tier()) {
			return candidate.tier() < current.tier();
		}
		return candidate.position().ordinal() < current.position().ordinal();
	}

	/** A winning tier along with the list it came from. */
	private record Best(TierList list, PlayerTiers tiers, Tier tier) {
	}

	/**
	 * The icon for the gamemode the tag is showing. When the slot tracks the
	 * player's best tier the gamemode is not fixed, so it is resolved by
	 * finding which mode actually holds that tier.
	 */
	private static Text iconFor(TierList list, Gamemode fixed, PlayerTiers tiers, Tier shown) {
		Gamemode mode = fixed;
		if (mode == null) {
			for (Map.Entry<Gamemode, Tier> entry : tiers.all().entrySet()) {
				if (entry.getValue().equals(shown)) {
					mode = entry.getKey();
					break;
				}
			}
		}
		return mode == null ? null : ModeIcons.of(list, mode.key());
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
