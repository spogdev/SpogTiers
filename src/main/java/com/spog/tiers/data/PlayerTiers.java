package com.spog.tiers.data;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** One tier list's view of one player. */
public final class PlayerTiers {
	private final TierList source;
	private final String name;
	private final Map<Gamemode, Tier> tiers = new EnumMap<>(Gamemode.class);
	private final Map<String, Tier> unknownModes = new LinkedHashMap<>();
	/** Tooltip facts, keyed by the same label the row is drawn with. */
	private final Map<String, TierDetail> details = new LinkedHashMap<>();
	private final long fetchedAtMillis;

	private String region = "";
	private int overall = 0;
	/** Fractional on MCPvP, where half points are awarded. */
	private float points = 0.0f;

	public PlayerTiers(TierList source, String name, long fetchedAtMillis) {
		this.source = source;
		this.name = name;
		this.fetchedAtMillis = fetchedAtMillis;
	}

	public TierList source() {
		return source;
	}

	public String name() {
		return name;
	}

	public long fetchedAtMillis() {
		return fetchedAtMillis;
	}

	public String region() {
		return region;
	}

	public void region(String region) {
		this.region = region == null ? "" : region;
	}

	public int overall() {
		return overall;
	}

	public void overall(int overall) {
		this.overall = overall;
	}

	public float points() {
		return points;
	}

	public void points(float points) {
		this.points = points;
	}

	public void put(Gamemode mode, Tier tier) {
		tiers.put(mode, tier);
	}

	/**
	 * Keeps a ranking whose gamemode we do not model yet, so a provider adding a
	 * new mode still shows up in the panel instead of silently vanishing.
	 */
	public void putUnknown(String label, Tier tier) {
		unknownModes.put(label, tier);
	}

	/** Records the tooltip detail for a row, keyed by its display label. */
	public void detail(String label, TierDetail detail) {
		if (detail != null && detail != TierDetail.EMPTY) {
			details.put(label, detail);
		}
	}

	public TierDetail detail(String label) {
		return details.getOrDefault(label, TierDetail.EMPTY);
	}

	public Tier get(Gamemode mode) {
		return tiers.getOrDefault(mode, Tier.UNRANKED);
	}

	public Map<Gamemode, Tier> all() {
		return tiers;
	}

	public Map<String, Tier> unknown() {
		return unknownModes;
	}

	public boolean hasAnyRanked() {
		return tiers.values().stream().anyMatch(Tier::isRanked)
				|| unknownModes.values().stream().anyMatch(Tier::isRanked);
	}

	/** Best (numerically lowest) ranked tier, HT beating MT beating LT. */
	public Tier best() {
		Tier best = null;
		for (Tier tier : tiers.values()) {
			if (!tier.isRanked()) {
				continue;
			}
			if (best == null
					|| tier.tier() < best.tier()
					|| (tier.tier() == best.tier()
							&& tier.position().ordinal() < best.position().ordinal())) {
				best = tier;
			}
		}
		return best;
	}
}
