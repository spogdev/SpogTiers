package com.spog.tiers.util;

import com.spog.tiers.SpogTiersClient;
import com.spog.tiers.client.ModeIcons;
import com.spog.tiers.config.SpogTiersConfig;
import com.spog.tiers.data.Gamemode;
import com.spog.tiers.data.PlayerTiers;
import com.spog.tiers.data.Regions;
import com.spog.tiers.data.Tier;
import com.spog.tiers.data.TierList;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

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
	public static Component withTag(UUID uuid, Component original) {
		SpogTiersConfig config = SpogTiersClient.config();
		if (config == null || !config.enabled) {
			return original;
		}

		Component left = tagFor(uuid, config.leftTag);
		Component right = tagFor(uuid, config.rightTag);
		Component region = config.showRegionOnNametag ? regionFor(uuid) : null;

		if (left == null && right == null && region == null) {
			return original;
		}

		MutableComponent out = Component.empty();
		if (region != null) {
			out.append(region).append(space());
		}
		if (left != null) {
			out.append(left).append(separator());
		}
		out.append(original);
		if (right != null) {
			out.append(separator()).append(right);
		}
		return out;
	}

	/** The badge for the left-hand slot, for compact contexts. */
	public static Component badgeFor(UUID uuid) {
		SpogTiersConfig config = SpogTiersClient.config();
		if (config == null || !config.enabled) {
			return null;
		}
		return tagFor(uuid, config.leftTag);
	}

	/** One configured side, or null when it is off or nothing is ranked. */
	private static Component tagFor(UUID uuid, SpogTiersConfig.TagSlot slot) {
		SpogTiersConfig config = SpogTiersClient.config();
		if (slot == null || !slot.enabled) {
			return null;
		}

		// A null list is the Best option: search every enabled list rather than
		// one in particular.
		TierList source = slot.list;
		PlayerTiers tiers;
		Tier tier;

		// Explicitly picking CatPVP still works; this only keeps it out of the
		// automatic choices.
		if (source != null && source.isCatPvp() && config.ignoreCatPvpInTags) {
			return null;
		}

		if (source == null) {
			Best best = bestAcrossLists(uuid, slot.gamemode);
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

		MutableComponent out = Component.empty();
		if (config.showTagIcons) {
			Component icon = iconFor(source, slot.gamemode, tiers, tier);
			if (icon != null) {
				out.append(icon).append(Component.literal(" "));
			}
		}
		out.append(Component.literal(tier.label())
				.setStyle(Style.EMPTY.withColor(tier.color())));
		return out;
	}

	/**
	 * The single best tier a player holds across every enabled list.
	 *
	 * <p>With no gamemode it considers every mode on every list; with one, only
	 * that mode, on the lists that rank it. The owning list comes back too, so
	 * the caller can draw that list's own artwork for the mode.
	 */
	private static Best bestAcrossLists(UUID uuid, Gamemode mode) {
		SpogTiersConfig config = SpogTiersClient.config();
		Best best = null;

		for (Map.Entry<TierList, PlayerTiers> entry
				: SpogTiersClient.cache().allLists(uuid).entrySet()) {
			TierList list = entry.getKey();
			PlayerTiers tiers = entry.getValue();
			if (tiers == null || !config.isEnabled(list)) {
				continue;
			}
			if (list.isCatPvp() && config.ignoreCatPvpInTags) {
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
	private static Component iconFor(TierList list, Gamemode fixed, PlayerTiers tiers, Tier shown) {
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
	private static Component regionFor(UUID uuid) {
		String code = Regions.resolve(SpogTiersClient.cache().allLists(uuid));
		if (code.isEmpty()) {
			return null;
		}
		return Component.literal(code)
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

	private static Component separator() {
		return Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY);
	}

	private static Component space() {
		return Component.literal(" ");
	}
}
