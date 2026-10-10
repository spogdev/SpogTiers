package dev.spog.tiers.data;

import dev.spog.tiers.SpogTiersClient;
import dev.spog.tiers.config.SpogTiersConfig;

import java.util.Locale;

/**
 * A single ranked tier.
 *
 * <p>{@code tier} is 1 (best) through 5. {@code position} is the prefix: most
 * lists only use HIGH/LOW, but PVPHQ also has MID.
 *
 * <p>{@code colorOverride} is non-zero when the provider supplied its own hex
 * colour, which only PVPHQ does. It is used for a named rank, where there is no
 * HT/LT row to draw from -- not for a numbered tier, whose colour comes from the
 * chosen palette so a tier code reads the same whichever list served it.
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

	/**
	 * Whether this rank should be presented as retired.
	 *
	 * <p>Public so a caller drawing its own marker can ask, rather than
	 * inferring it by comparing label() against bareLabel(). Honours the Show
	 * Retired setting, so a retired rank hidden there stays hidden here.
	 */
	public boolean showsRetirement() {
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
	 * ARGB colour for this tier, from the palette the user picked.
	 *
	 * <p>The same palette is used for every list, so a tier code reads the same
	 * wherever it appears. Each row is HT, MT, LT for one tier number.
	 *
	 * <p>MCTiers are the {@code --ht<n>-foreground} / {@code --lt<n>-foreground}
	 * CSS variables from mctiers.com. PVPHQ's are the colours it sends with each
	 * ranking, read from its own palette table. PvPTiers publishes one colour per
	 * tier number -- every rule in its stylesheet pairs {@code _ht<n>} with
	 * {@code _lt<n>} -- so its HT and LT are that one hue taken lighter and
	 * darker rather than two colours it does not define.
	 *
	 * <p>Every MT is the midpoint of the HT and LT beside it.
	 *
	 * <p>The rows are stored already lightened where the source colours were
	 * picked against a light page and are close to unreadable on ours: PVPHQ's
	 * LT5 sits at 1.6:1 against the panel and PvPTiers' tier 4 at 2.8:1. Running
	 * each colour through {@link #legible} on its own would fix the contrast but
	 * flatten the row, because three shades of one hue all stop at the same
	 * contrast floor and come out as a single colour -- and the HT/LT difference
	 * is the thing being drawn. So each row was raised by one lightness step
	 * chosen by its darkest member, which clears 4.5:1 for all three and keeps
	 * them visibly apart. They are constants so the game is not doing colour
	 * maths every frame.
	 */
	private static final int[][][] PALETTES = {
			// MCTiers: shades of the site's own foregrounds. Tier 1 is a lifted
			// #f2ac44, whose own foreground reads beige against our darker panel.
			{
					{0xFFFFC14C, 0xFFF2AC44, 0xFFD5973C},
					{0xFFC4D3E7, 0xFFB2BDCC, 0xFFA0A7B2},
					{0xFFF89F5A, 0xFFDF8D4E, 0xFFC67B42},
					{0xFF81749A, 0xFF736789, 0xFF655B79},
					{0xFF8F82A8, 0xFF7A6E90, 0xFF655B79},
			},
			// PvPTiers: #ffd04d, #c2d4e1, #a1503d, #b02937, #bcbbc1 spread by
			// +/-7% lightness. Tiers 3 and 4 are dark enough on the site that the
			// whole triad is lifted; 1, 2 and 5 are used as published.
			{
					{0xFFFFD971, 0xFFFFD04D, 0xFFFFC729},
					{0xFFDAE5ED, 0xFFC2D4E1, 0xFFAAC3D5},
					{0xFFD29788, 0xFFC87F6E, 0xFFBE6854},
					{0xFFE48D96, 0xFFDD6F7B, 0xFFD75260},
					{0xFFCECED2, 0xFFBCBBC1, 0xFFAAA8B0},
			},
			// PVPHQ: its HT1 #FFC53F ... LT5 #4C3822, lifted where needed. Tier 1
			// is used as published; tier 5's LT is near black on the site and
			// needs the most lift of any row here to be visible at all.
			{
					{0xFFFFC53F, 0xFFDFAC37, 0xFFBF942F},
					{0xFFA5B4D5, 0xFF8B9AB7, 0xFF72809A},
					{0xFFE39568, 0xFFCF8052, 0xFFBC6B3C},
					{0xFFBCC1C4, 0xFF9CA1A4, 0xFF7C8184},
					{0xFFCAAB89, 0xFFB69169, 0xFFA37849},
			},
	};

	/** The palette the user picked, defaulting to MCTiers before config loads. */
	private static SpogTiersConfig.TierPalette palette() {
		SpogTiersConfig config = SpogTiersClient.config();
		return config == null || config.tierPalette == null
				? SpogTiersConfig.TierPalette.MCTIERS
				: config.tierPalette;
	}

	/**
	 * The HT, MT and LT colours for a tier number, in that order.
	 *
	 * <p>Public so the config screen can show a row of examples beside the
	 * palette dropdown without reconstructing a Tier per swatch.
	 */
	public static int[] paletteRow(SpogTiersConfig.TierPalette choice, int tier) {
		int[][] rows = PALETTES[choice.ordinal()];
		// Anything outside 1-5 reads as tier 5, matching color()'s own default
		// branch: the lists occasionally send a 6 and it should not throw.
		int index = tier >= 1 && tier <= rows.length ? tier - 1 : rows.length - 1;
		return rows[index];
	}

	public int color() {
		// CatPVP's named ranks keep their own hues: they have no HT/LT row to
		// borrow, so the colour the provider sent is all there is to draw.
		// Numbered tiers below take the palette instead, whatever a provider
		// sent with them -- a tier code reading the same everywhere is the
		// whole point, and PVPHQ is the only list that sends colours at all.
		if (isNamed()) {
			return colorOverride != 0 ? legible(colorOverride) : 0xFFD5DCE5;
		}
		if (!isRanked()) {
			return 0xFF9CA3AF;
		}
		int base = paletteRow(palette(), tier)[position.ordinal()];
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
