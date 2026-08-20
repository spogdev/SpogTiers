package com.spog.tiers.data;

import java.util.EnumMap;
import java.util.Map;

/** All known tiers for one player, plus the time they were fetched. */
public final class PlayerTiers {
	private final String name;
	private final Map<Gamemode, Tier> tiers = new EnumMap<>(Gamemode.class);
	private final long fetchedAtMillis;

	public PlayerTiers(String name, long fetchedAtMillis) {
		this.name = name;
		this.fetchedAtMillis = fetchedAtMillis;
	}

	public String name() {
		return name;
	}

	public long fetchedAtMillis() {
		return fetchedAtMillis;
	}

	public void put(Gamemode mode, Tier tier) {
		tiers.put(mode, tier);
	}

	public Tier get(Gamemode mode) {
		return tiers.getOrDefault(mode, Tier.UNRANKED);
	}

	public boolean hasAnyRanked() {
		return tiers.values().stream().anyMatch(Tier::isRanked);
	}

	public Map<Gamemode, Tier> all() {
		return tiers;
	}

	/** The player's best (numerically lowest) ranked tier, or null if unranked. */
	public Tier best() {
		Tier best = null;
		for (Tier tier : tiers.values()) {
			if (!tier.isRanked()) {
				continue;
			}
			if (best == null || tier.tier() < best.tier()
					|| (tier.tier() == best.tier() && tier.high() && !best.high())) {
				best = tier;
			}
		}
		return best;
	}
}
