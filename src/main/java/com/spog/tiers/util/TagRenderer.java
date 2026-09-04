package com.spog.tiers.util;

import com.spog.tiers.SpogTiersClient;
import com.spog.tiers.client.ModeIcons;
import com.spog.tiers.config.SpogTiersConfig;
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

import java.util.Locale;
import java.util.Map;
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

		Text door = doorTag(uuid);

		Resolved leftSlot = resolve(uuid, config.leftTag, null);
		// The right slot is told what the left landed on, so it can avoid
		// repeating it when the user has asked for that.
		//
		// Not when the left is about to be replaced by our own tierlist,
		// though: the tier it would have repeated is not going to be on show,
		// so suppressing the right one leaves the pair saying less than either
		// slot alone. The two are only duplicates while both are drawn.
		String exclude = config.preventDuplicateTiers
				&& leftSlot != null
				&& !(door != null && isDiaSmp(leftSlot))
				? leftSlot.label() : null;
		Resolved rightSlot = resolve(uuid, config.rightTag, exclude);

		Text left = leftSlot == null ? null : leftSlot.text();
		Text right = rightSlot == null ? null : rightSlot.text();

		// Our own tierlist takes a slot when the one it would sit in is showing
		// a Diamond SMP tier: that is the mode Door SMP is played at, so the
		// two are saying the same thing and ours is the more specific.
		if (door != null) {
			if (isDiaSmp(leftSlot)) {
				left = door;
			} else if (isDiaSmp(rightSlot)) {
				right = door;
			}
		}

		Text region = config.showRegionOnNametag ? regionFor(uuid) : null;

		if (left == null && right == null && region == null) {
			return original;
		}

		MutableText out = Text.empty();
		// No leading space: the backdrop already extends a couple of pixels
		// past the text on its own, which is enough to keep another mod's
		// badge from butting against ours -- Essential puts its badge
		// immediately to the left, and a space on top of the backdrop's own
		// margin left a visibly wide gap.
		if (region != null) {
			out.append(region).append(space());
		}
		if (left != null) {
			out.append(left).append(separator());
		}
		out.append(original);
		// No trailing space either, for the same reason, and because an odd
		// space on one end only makes the name asymmetric -- which offsets its
		// backdrop from the above tag's and leaves a bar down one side.
		if (right != null) {
			out.append(separator()).append(right);
		}
		return out;
	}

	/** The badge for the left-hand slot, for compact contexts. */
	public static Text badgeFor(UUID uuid) {
		SpogTiersConfig config = SpogTiersClient.config();
		if (config == null || !config.enabled) {
			return null;
		}
		Resolved slot = resolve(uuid, config.leftTag, null);
		return slot == null ? null : slot.text();
	}

	/**
	 * The tag for the line above the name, or null when that slot is off.
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
		// No exclusion here. Prevent Duplicates is about the left and right
		// slots that sit on the same line -- "the same tier appearing in both
		// slots" -- and applying it to this one as well meant the above tag
		// silently vanished whenever it agreed with the left tag, which is
		// most of the time: both default to the player's best tier.
		Resolved above = resolve(uuid, config.aboveTag, null);
		return above == null ? null : above.text();
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
	 * @param exclude a tier label the slot must not repeat, or null for no
	 *     restriction. A Best slot searches past it for the next best thing; a
	 *     slot pinned to one list has nowhere else to look and shows nothing.
	 */
	private static Resolved resolve(UUID uuid, SpogTiersConfig.TagSlot slot, String exclude) {
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
		// A pinned slot has only one answer, so if that is the excluded one it
		// simply has nothing to show.
		if (exclude != null && exclude.equals(tier.label())) {
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

	/** True when a slot settled on a Diamond SMP ranking. */
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
			// A narrower gap than the other icons take: this glyph was shifted
			// right inside its cell, which widened its advance, so a full space
			// after it left the tier sitting noticeably away from the door.
			out.append(ModeIcons.doorRaised()).append(ModeIcons.narrowSpace());
		}
		out.append(Text.literal(grade.label())
				.setStyle(Style.EMPTY.withColor(grade.foreground() & 0xFFFFFF)));
		return out;
	}

	/**
	 * The single best tier a player holds across every enabled list.
	 *
	 * <p>With no gamemode it considers every mode on every list; with one, only
	 * that mode, on the lists that rank it. The owning list comes back too, so
	 * the caller can draw that list's own artwork for the mode.
	 */
	private static Best bestAcrossLists(UUID uuid, Gamemode mode, String exclude) {
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
			if (exclude != null && exclude.equals(candidate.label())) {
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
	 * What goes between two parts of a nametag.
	 *
	 * <p>A plain space when separators are switched off: the parts still need
	 * holding apart, and dropping the bar without it would run the badge into
	 * the name.
	 */
	private static Text separator() {
		SpogTiersConfig config = SpogTiersClient.config();
		if (config != null && !config.showSeparators) {
			return space();
		}
		return Text.literal(" | ").formatted(Formatting.DARK_GRAY);
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
