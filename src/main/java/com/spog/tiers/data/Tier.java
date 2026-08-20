package com.spog.tiers.data;

/**
 * A single ranked tier. {@code tier} is 1 (best) through 5 (worst);
 * {@code high} distinguishes HT (high tier) from LT (low tier).
 */
public record Tier(int tier, boolean high, boolean retired) {
	public static final Tier UNRANKED = new Tier(0, false, false);

	public boolean isRanked() {
		return tier >= 1 && tier <= 5;
	}

	/** Short display label, e.g. "HT1" or "LT3". */
	public String label() {
		if (!isRanked()) {
			return "?";
		}
		return (high ? "HT" : "LT") + tier;
	}

	/**
	 * ARGB colour for this tier. Higher tiers are warmer; retired players are
	 * rendered desaturated so they read as stale at a glance.
	 */
	public int color() {
		if (!isRanked()) {
			return 0xFF888888;
		}
		int base = switch (tier) {
			case 1 -> 0xFFFF5555;
			case 2 -> 0xFFFFAA00;
			case 3 -> 0xFFFFFF55;
			case 4 -> 0xFF55FF55;
			default -> 0xFF55FFFF;
		};
		return retired ? desaturate(base) : base;
	}

	private static int desaturate(int argb) {
		int r = (argb >> 16) & 0xFF;
		int g = (argb >> 8) & 0xFF;
		int b = argb & 0xFF;
		int grey = (r * 30 + g * 59 + b * 11) / 100;
		r = (r + grey * 2) / 3;
		g = (g + grey * 2) / 3;
		b = (b + grey * 2) / 3;
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}
}
