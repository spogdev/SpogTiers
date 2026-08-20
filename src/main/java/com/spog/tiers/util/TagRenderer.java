package com.spog.tiers.util;

import com.spog.tiers.SpogTiersClient;
import com.spog.tiers.client.ModeIcons;
import com.spog.tiers.config.SpogTiersConfig;
import com.spog.tiers.data.Gamemode;
import com.spog.tiers.data.PlayerTiers;
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

	/** The badge for whichever list the user favourited, for compact contexts. */
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
		if (slot == null || !slot.enabled || slot.list == null) {
			return null;
		}
		if (!config.isEnabled(slot.list)) {
			return null;
		}

		PlayerTiers tiers = SpogTiersClient.cache().get(uuid, slot.list);
		if (tiers == null) {
			return null;
		}

		Tier tier = slot.gamemode == null ? tiers.best() : tiers.get(slot.gamemode);
		if (tier == null || !tier.isRanked()) {
			return null;
		}

		MutableComponent out = Component.empty();
		if (config.showTagIcons) {
			Component icon = iconFor(slot.list, slot.gamemode, tiers, tier);
			if (icon != null) {
				out.append(icon).append(Component.literal(" "));
			}
		}
		out.append(Component.literal(tier.label())
				.setStyle(Style.EMPTY.withColor(tier.color())));
		return out;
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

	/** The player's region as a small coloured prefix. */
	private static Component regionFor(UUID uuid) {
		Map<TierList, PlayerTiers> all = SpogTiersClient.cache().allLists(uuid);
		for (TierList list : TierList.values()) {
			PlayerTiers tiers = all.get(list);
			if (tiers != null && !tiers.region().isEmpty() && tiers.region().length() <= 4) {
				String code = tiers.region().toUpperCase(Locale.ROOT);
				return Component.literal(code)
						.setStyle(Style.EMPTY.withColor(regionColor(code)));
			}
		}
		return null;
	}

	private static int regionColor(String region) {
		return switch (region) {
			case "NA" -> 0xD95C6A;
			case "EU" -> 0x89F19C;
			case "AS" -> 0xAF7F91;
			case "AU", "OCE" -> 0xD5AD80;
			case "SA" -> 0x5DCCDC;
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
