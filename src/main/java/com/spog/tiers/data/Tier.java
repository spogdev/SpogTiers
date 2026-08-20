package com.spog.tiers.data;

import java.util.Locale;

/**
 * A single ranked tier.
 *
 * <p>{@code tier} is 1 (best) through 5. {@code position} is the prefix: most
 * lists only use HIGH/LOW, but PVPHQ also has MID. {@code colorOverride} is
 * non-zero when the provider supplied its own hex colour (PVPHQ does), in which
 * case it wins over the built-in palette so our display matches the site.
 */
public record Tier(int tier, Position position, boolean retired, int colorOverride) {
	public static final Tier UNRANKED = new Tier(0, Position.HIGH, false, 0);

	public enum Position {
		HIGH("HT"),
		MID("MT"),
		LOW("LT");

		private final String prefix;

		Position(String prefix) {
			this.prefix = prefix;
		}

		public String prefix() {
			return prefix;
		}
	}

	public Tier(int tier, Position position, boolean retired) {
		this(tier, position, retired, 0);
	}

	public boolean isRanked() {
		return tier >= 1 && tier <= 5;
	}

	/** Short display label, e.g. "HT1" or "MT3". */
	public String label() {
		return isRanked() ? position.prefix() + tier : "?";
	}

	/**
	 * Parses a label like {@code "HT3"}, {@code "MT3"} or {@code "Unranked"}.
	 * Returns {@link #UNRANKED} for anything unrecognised.
	 */
	public static Tier parseLabel(String raw, int colorOverride) {
		if (raw == null || raw.length() < 3) {
			return UNRANKED;
		}
		String text = raw.trim().toUpperCase(Locale.ROOT);
		Position position = switch (text.substring(0, 2)) {
			case "HT" -> Position.HIGH;
			case "MT" -> Position.MID;
			case "LT" -> Position.LOW;
			default -> null;
		};
		if (position == null) {
			return UNRANKED;
		}
		try {
			return new Tier(Integer.parseInt(text.substring(2).trim()), position, false, colorOverride);
		} catch (NumberFormatException e) {
			return UNRANKED;
		}
	}

	/** ARGB colour: the provider's own if it gave one, else our palette. */
	public int color() {
		if (colorOverride != 0) {
			return 0xFF000000 | colorOverride;
		}
		if (!isRanked()) {
			return 0xFF9CA3AF;
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
		return 0xFF000000
				| (((r + grey * 2) / 3) << 16)
				| (((g + grey * 2) / 3) << 8)
				| ((b + grey * 2) / 3);
	}
}
