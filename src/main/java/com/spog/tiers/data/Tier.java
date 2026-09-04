package com.spog.tiers.data;

import com.spog.tiers.SpogTiersClient;
import com.spog.tiers.config.SpogTiersConfig;

import java.util.Locale;

/**
 * A single ranked tier.
 *
 * <p>{@code tier} is 1 (best) through 5. {@code position} is the prefix: most
 * lists only use HIGH/LOW, but PVPHQ also has MID. {@code colorOverride} is
 * non-zero when the provider supplied its own hex colour (PVPHQ does), in which
 * case it wins over the built-in palette so our display matches the site.
 */
public record Tier(int tier, Position position, boolean retired, int colorOverride,
		String namedRank) {
	public static final Tier UNRANKED = new Tier(0, Position.HIGH, false, 0, null);

	/** Roughly the card fill over the dimmed backdrop, for contrast checks. */
	private static final int PANEL_BACKGROUND = 0xFF12171E;
	/** Minimum contrast a rank colour must reach to be used as text. */
	private static final float MIN_CONTRAST = 4.5f;

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
		this(tier, position, retired, 0, null);
	}

	public Tier(int tier, Position position, boolean retired, int colorOverride) {
		this(tier, position, retired, colorOverride, null);
	}

	/**
	 * A tier from a list that names its ranks instead of numbering them, as
	 * CatPVP does ("Netherite I", "Diamond III").
	 *
	 * <p>The rank name and colour come from the provider, so the display matches
	 * the site exactly rather than guessing at a ladder. {@code tier} and
	 * {@code position} are still derived from the name so that sorting, "best
	 * tier" and the rest of the mod keep working unchanged.
	 */
	public static Tier named(String rankName, int color) {
		if (rankName == null || rankName.isBlank()) {
			return UNRANKED;
		}
		String name = rankName.trim();
		return new Tier(ladderTier(name), ladderPosition(name), false, color, name);
	}

	/** True when this carries a provider-named rank rather than a tier number. */
	public boolean isNamed() {
		return namedRank != null && !namedRank.isEmpty();
	}

	public boolean isRanked() {
		// A named rank is always a real placement; its tier number only exists
		// so it sorts against the numbered lists, and Champion sits at 0.
		return isNamed() ? tier >= 0 && tier <= 5 : tier >= 1 && tier <= 5;
	}

	/**
	 * Whether retirement is being shown at all.
	 *
	 * <p>Read from the config so that turning it off presents a retired rank
	 * as the plain tier it is -- the R dropped and the colour left at full
	 * strength -- everywhere this record is drawn. The config is consulted
	 * rather than passed in because every caller of {@link #label()} and
	 * {@link #color()} would otherwise have to thread the flag through, and
	 * one that forgot would quietly disagree with the rest.
	 */
	private static boolean showRetired() {
		SpogTiersConfig config = SpogTiersClient.config();
		return config == null || config.showRetired;
	}

	/** Whether this rank should be presented as retired. */
	private boolean showsRetirement() {
		return retired && showRetired();
	}

	/**
	 * Short display label, e.g. {@code HT1} or {@code MT3}. Retired ranks are
	 * prefixed with an R, so a retired HT1 reads {@code RHT1} -- unless
	 * retirement is switched off, when it reads as the plain {@code HT1}.
	 */
	public String label() {
		if (isNamed()) {
			return abbreviate(namedRank);
		}
		if (!isRanked()) {
			return "?";
		}
		return (showsRetirement() ? "R" : "") + position.prefix() + tier;
	}

	/** The label without the retired marker, e.g. {@code LT2} for an RLT2. */
	public String bareLabel() {
		if (isNamed()) {
			return abbreviate(namedRank);
		}
		return isRanked() ? position.prefix() + tier : "?";
	}

	/** The full rank name for tooltips, e.g. {@code Netherite I}. */
	public String fullName() {
		return isNamed() ? namedRank : label();
	}

	/**
	 * Shortens a named rank to initial plus number: {@code Netherite I} becomes
	 * {@code N1}, {@code Diamond III} becomes {@code D3}. The cards are narrow,
	 * and the tooltip carries the full name.
	 */
	private static String abbreviate(String rankName) {
		String[] parts = rankName.trim().split("\\s+");
		String letter = parts[0].substring(0, 1).toUpperCase(Locale.ROOT);
		if (parts.length < 2) {
			return letter;
		}
		int number = roman(parts[1]);
		return number > 0 ? letter + number : letter;
	}

	/** Roman numeral to int, for the {@code I}-{@code V} suffixes; 0 if absent. */
	private static int roman(String raw) {
		return switch (raw.toUpperCase(Locale.ROOT)) {
			case "I" -> 1;
			case "II" -> 2;
			case "III" -> 3;
			case "IV" -> 4;
			case "V" -> 5;
			default -> 0;
		};
	}

	/**
	 * Maps a named rank onto 1-5 so it sorts against numbered tiers.
	 *
	 * <p>CatPVP's ladder runs Champion, Netherite, Diamond, Emerald, Gold, Iron,
	 * Stone, Wood, with Champion the best. Anything unrecognised lands mid-table
	 * rather than claiming to be the best or worst.
	 */
	private static int ladderTier(String rankName) {
		String metal = rankName.trim().split("\\s+")[0].toUpperCase(Locale.ROOT);
		return switch (metal) {
			// Champion is the top of the ladder and has no numeral, so it needs
			// to outrank even Netherite III rather than tying with it.
			case "CHAMPION" -> 0;
			case "NETHERITE" -> 1;
			case "DIAMOND" -> 2;
			case "EMERALD" -> 3;
			case "GOLD" -> 4;
			case "IRON", "COPPER" -> 5;
			default -> 3;
		};
	}

	/**
	 * The sub-rank within a metal, so Netherite III sorts above Netherite I.
	 *
	 * <p>CatPVP counts upward (III is better than I), which is the opposite of
	 * the HT/LT convention, so the numeral is inverted here.
	 */
	private static Position ladderPosition(String rankName) {
		String[] parts = rankName.trim().split("\\s+");
		if (parts[0].equalsIgnoreCase("Champion")) {
			return Position.HIGH;
		}
		return switch (parts.length < 2 ? 0 : roman(parts[1])) {
			case 3, 4, 5 -> Position.HIGH;
			case 2 -> Position.MID;
			default -> Position.LOW;
		};
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

	/**
	 * ARGB colour for this tier, using MCTiers' palette for every list so the
	 * codes read consistently. These are the {@code --ht<n>-foreground} /
	 * {@code --lt<n>-foreground} CSS variables from mctiers.com; MT (PVPHQ
	 * only) sits between its neighbouring HT and LT shades.
	 */
	public int color() {
		// A provider-supplied colour always wins, so CatPVP's ranks keep their
		// own hues rather than borrowing the HT/LT palette.
		if (isNamed()) {
			return colorOverride != 0 ? legible(colorOverride) : 0xFFD5DCE5;
		}
		if (!isRanked()) {
			return 0xFF9CA3AF;
		}
		int base = switch (tier) {
			// Shades of #f2ac44: MCTiers' own tier-1 foreground reads beige
			// in-game against our darker panel.
			case 1 -> switch (position) {
				case HIGH -> 0xFFFFC14C;
				case MID -> 0xFFF2AC44;
				case LOW -> 0xFFD5973C;
			};
			case 2 -> switch (position) {
				case HIGH -> 0xFFC4D3E7;
				case MID -> 0xFFB2BDCC;
				case LOW -> 0xFFA0A7B2;
			};
			case 3 -> switch (position) {
				case HIGH -> 0xFFF89F5A;
				case MID -> 0xFFDF8D4E;
				case LOW -> 0xFFC67B42;
			};
			case 4 -> switch (position) {
				case HIGH -> 0xFF81749A;
				case MID -> 0xFF736789;
				case LOW -> 0xFF655B79;
			};
			default -> switch (position) {
				case HIGH -> 0xFF8F82A8;
				case MID -> 0xFF7A6E90;
				case LOW -> 0xFF655B79;
			};
		};
		return showsRetirement() ? desaturate(base) : base;
	}

	/**
	 * Lightens a provider colour just enough to read on the dark panel.
	 *
	 * <p>CatPVP draws its ranks as badges on a light chip, so some of its hues
	 * are very dark -- Netherite is {@code #443A3B}, which sits at about 1.6:1
	 * against our background and is effectively invisible as plain text. The
	 * hue and saturation are kept and only the lightness is raised, and only
	 * until the colour is readable, so anything already bright (Diamond,
	 * Emerald) is passed through untouched.
	 */
	private static int legible(int rgb) {
		float[] hsl = toHsl((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
		int out = 0xFF000000 | rgb;

		// Raise lightness in small steps rather than jumping to a fixed value,
		// which would flatten distinct ranks into the same shade.
		for (int step = 0; step < 100 && contrast(out) < MIN_CONTRAST; step++) {
			hsl[2] = Math.min(1.0f, hsl[2] + 0.006f);
			out = 0xFF000000 | toRgb(hsl[0], hsl[1], hsl[2]);
		}
		return out;
	}

	/** WCAG contrast of an ARGB colour against the panel background. */
	private static float contrast(int argb) {
		float a = relativeLuminance(argb);
		float b = relativeLuminance(PANEL_BACKGROUND);
		float high = Math.max(a, b);
		float low = Math.min(a, b);
		return (high + 0.05f) / (low + 0.05f);
	}

	private static float relativeLuminance(int argb) {
		return 0.2126f * channelLuminance((argb >> 16) & 0xFF)
				+ 0.7152f * channelLuminance((argb >> 8) & 0xFF)
				+ 0.0722f * channelLuminance(argb & 0xFF);
	}

	private static float channelLuminance(int value) {
		float c = value / 255.0f;
		return c <= 0.03928f ? c / 12.92f : (float) Math.pow((c + 0.055f) / 1.055f, 2.4);
	}

	private static float[] toHsl(int r, int g, int b) {
		float rf = r / 255.0f;
		float gf = g / 255.0f;
		float bf = b / 255.0f;
		float max = Math.max(rf, Math.max(gf, bf));
		float min = Math.min(rf, Math.min(gf, bf));
		float lightness = (max + min) / 2.0f;
		float hue = 0.0f;
		float saturation = 0.0f;

		if (max != min) {
			float delta = max - min;
			saturation = lightness > 0.5f
					? delta / (2.0f - max - min)
					: delta / (max + min);
			if (max == rf) {
				hue = (gf - bf) / delta + (gf < bf ? 6.0f : 0.0f);
			} else if (max == gf) {
				hue = (bf - rf) / delta + 2.0f;
			} else {
				hue = (rf - gf) / delta + 4.0f;
			}
			hue /= 6.0f;
		}
		return new float[] {hue, saturation, lightness};
	}

	private static int toRgb(float hue, float saturation, float lightness) {
		if (saturation == 0.0f) {
			int grey = Math.round(lightness * 255.0f);
			return (grey << 16) | (grey << 8) | grey;
		}
		float q = lightness < 0.5f
				? lightness * (1.0f + saturation)
				: lightness + saturation - lightness * saturation;
		float p = 2.0f * lightness - q;
		int r = Math.round(hueToChannel(p, q, hue + 1.0f / 3.0f) * 255.0f);
		int g = Math.round(hueToChannel(p, q, hue) * 255.0f);
		int b = Math.round(hueToChannel(p, q, hue - 1.0f / 3.0f) * 255.0f);
		return (r << 16) | (g << 8) | b;
	}

	private static float hueToChannel(float p, float q, float t) {
		float value = t;
		if (value < 0.0f) {
			value += 1.0f;
		}
		if (value > 1.0f) {
			value -= 1.0f;
		}
		if (value < 1.0f / 6.0f) {
			return p + (q - p) * 6.0f * value;
		}
		if (value < 0.5f) {
			return q;
		}
		if (value < 2.0f / 3.0f) {
			return p + (q - p) * (2.0f / 3.0f - value) * 6.0f;
		}
		return p;
	}

	/**
	 * A total order over named ranks, lower being better.
	 *
	 * <p>{@link #tier()} and {@link #position()} exist so a named rank can be
	 * compared against the numbered lists, but they are a lossy fit: the metals
	 * outnumber the five tiers, so Gold and Iron share tier 4 and Stone, Wood
	 * and Coal share tier 5. Sorting CatPVP by that alone leaves those ranks
	 * interleaved. This keeps the metal and its numeral apart, so the ladder
	 * comes out in the order the site shows it.
	 */
	public int ladderOrdinal() {
		if (!isNamed()) {
			// Numbered lists: tier then HT/MT/LT, matching their own ordering.
			return (tier * 10 + position.ordinal()) * 2;
		}
		String[] parts = namedRank.trim().split("\\s+");
		// CatPVP counts upward inside a metal (III beats I), so the numeral is
		// inverted to keep "lower is better".
		int within = parts.length < 2 ? 0 : 5 - roman(parts[1]);
		// Doubled so a named rank never lands exactly on an HT/MT/LT slot: a
		// tie there would order by whichever row happened to come first.
		return namedBase(parts[0]) * 2 + within * 2 + 1;
	}

	/**
	 * Where a metal starts on the shared ladder, best first.
	 *
	 * <p>CatPVP runs Copper, Iron, Gold, Emerald, Diamond, Netherite, Champion,
	 * so Emerald sits above Gold and Diamond above Emerald -- not the ordering
	 * the material names suggest.
	 *
	 * <p>The bases are picked against the HT/LT scale, where tier n spans
	 * {@code n * 10} to {@code n * 10 + 2}, because CatPVP's ranks read a good
	 * deal stronger than they play: mapped evenly, a Diamond III outranked an
	 * HT3. Diamond now straddles HT4 -- its top grade still beats HT4, the
	 * lower two fall below it -- and the metals beneath follow from there.
	 */
	private static int namedBase(String metal) {
		return switch (metal.toUpperCase(Locale.ROOT)) {
			// Champion and Netherite stay above the HT1/HT2 band.
			case "CHAMPION" -> 8;
			case "NETHERITE" -> 20;
			// Named ranks carry a +1 tiebreak below (see ladderOrdinal), so
			// Diamond III lands just above HT4 while II and I fall just below
			// it -- HT4 outranks Diamond down to Diamond II, as intended.
			case "DIAMOND" -> 37;
			case "EMERALD" -> 46;
			case "GOLD" -> 56;
			case "IRON" -> 66;
			case "COPPER" -> 76;
			// An unseen rank sorts below everything known rather than claiming
			// a place in the middle of the ladder.
			default -> 86;
		};
	}

	/**
	 * Dims a retired tier without washing out its hue.
	 *
	 * <p>The old blend pulled two thirds of the way to grey, which turned the
	 * tier-1 gold into beige. Darkening while keeping most of the saturation
	 * still reads as "retired" but leaves the colour identifiable.
	 */
	private static int desaturate(int argb) {
		int r = (argb >> 16) & 0xFF;
		int g = (argb >> 8) & 0xFF;
		int b = argb & 0xFF;
		int grey = (r * 30 + g * 59 + b * 11) / 100;

		// 25% toward grey, then a slight darkening.
		r = (int) (((r * 3 + grey) / 4) * 0.82f);
		g = (int) (((g * 3 + grey) / 4) * 0.82f);
		b = (int) (((b * 3 + grey) / 4) * 0.82f);
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}
}
