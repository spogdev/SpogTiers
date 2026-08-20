package com.spog.tiers.util;

import com.spog.tiers.SpogTiersClient;
import com.spog.tiers.config.SpogTiersConfig;
import com.spog.tiers.data.PlayerTiers;
import com.spog.tiers.data.Tier;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.UUID;

/**
 * Builds the coloured tier prefix that is spliced in front of a player's name.
 * Every method is null-safe and returns the original text unchanged when no
 * tier is known, so callers can apply it unconditionally.
 */
public final class TagRenderer {
	private TagRenderer() {
	}

	/** Returns {@code original} with a tier badge prepended, if one is known. */
	public static Component withTag(UUID uuid, Component original) {
		Component badge = badgeFor(uuid);
		if (badge == null) {
			return original;
		}
		return Component.empty().append(badge).append(Component.literal(" ")).append(original);
	}

	/** The badge alone, e.g. a bracketed "HT2", or null when unranked/unknown. */
	public static Component badgeFor(UUID uuid) {
		SpogTiersConfig config = SpogTiersClient.config();
		if (config == null || !config.enabled) {
			return null;
		}
		PlayerTiers tiers = SpogTiersClient.cache().get(uuid);
		if (tiers == null) {
			return null;
		}

		Tier tier = config.showBestTier ? tiers.best() : tiers.get(config.displayMode);
		if (tier == null || !tier.isRanked()) {
			return null;
		}

		MutableComponent label = Component.literal(tier.label())
				.setStyle(Style.EMPTY.withColor(tier.color()));
		return Component.literal("[").withStyle(ChatFormatting.DARK_GRAY)
				.append(label)
				.append(Component.literal("]").withStyle(ChatFormatting.DARK_GRAY));
	}
}
