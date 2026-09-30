package dev.spog.tiers.client;

import dev.spog.tiers.SpogTiers;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;

/**
 * The trial chamber spark, as the aura draws it.
 *
 * <p>Vanilla's ominous trial spawner spits these out when it notices a player:
 * a thin vertical streak that shortens and brightens as it dies. This is that
 * particle's look, taken from the game's own definition rather than
 * approximated -- the five sprites are vanilla's, and the growth curve and
 * facing below are {@code TrialSpawnerDetectionParticle}'s.
 *
 * <p>What is deliberately not vanilla's is the colour. The sprites are teal,
 * and a teal aura on every graded player would say nothing about their grade;
 * the tier colour is the whole point of the aura. So the sprite is read as a
 * brightness ramp -- the streak's head is light, its tail dark -- and that ramp
 * is applied to the tier colour. A B-tier player gets a cyan spark and an
 * A-tier player a red one, both with the trial chamber's shape and motion.
 *
 * <p>Motion stays {@link WorldAura}'s. The two are separate concerns: that
 * class says where a mote is and how bright, this one says what is drawn
 * there, and the profile screen and the world both go through both.
 */
public final class TrialSparks {
	/** How many sprites vanilla's particle animates through. */
	public static final int FRAMES = 5;

	/**
	 * The sprites, in the order the particle plays them.
	 *
	 * <p>Vanilla's shape, but grey: {@code tools/spark_sprites.py} bakes them
	 * from the game's own files. The draw multiplies the vertex colour by the
	 * texture, so a teal sprite tinted yellow came out dark green -- a C-tier
	 * aura was only its own colour on the last, near-white frame. Grey texture
	 * times grade colour is the grade colour, at the brightness the streak has
	 * along its length.
	 */
	private static final Identifier[] SPRITES = new Identifier[FRAMES];

	static {
		for (int i = 0; i < FRAMES; i++) {
			SPRITES[i] = Identifier.fromNamespaceAndPath(SpogTiers.MOD_ID,
					"textures/particle/spark_" + i + ".png");
		}
	}

	/** The sprite sheet each frame is drawn on, in pixels. */
	public static final int SPRITE_SIZE = 16;

	/**
	 * Where the drawn streak sits inside that square.
	 *
	 * <p>A fixed window, not each frame's own bounds: the frames narrow as the
	 * spark dies, and sampling each one tightly would snap the streak's width
	 * between frames instead of letting it shorten in place.
	 */
	public static final int STREAK_LEFT = 0;
	public static final int STREAK_TOP = 1;
	public static final int STREAK_WIDTH = 1;

	/**
	 * How long the streak is in each frame, in pixels.
	 *
	 * <p>The frames shorten as the spark dies, which is how vanilla shows it
	 * burning out. A caller drawing the texture needs the real length to keep
	 * that proportion rather than stretching every frame to the same box.
	 */
	private static final int[] HEIGHTS = {12, 8, 6, 4, 2};

	/**
	 * How wide a drawn streak is against its height.
	 *
	 * <p>Wider than the sprite's own one-in-six. That ratio is right for a
	 * texture, where a column of pixels is a column whatever its size, but a
	 * streak that long would come out well under a pixel across and all but
	 * vanish.
	 *
	 * <p>A sixth: skinny, and the widest the streak can be before the bands
	 * down its length start to look like a bar rather than a spark.
	 */
	public static final float ASPECT = 1.0f / 6.0f;

	/**
	 * Vanilla's quad size for this particle, in blocks, before its own scale.
	 *
	 * <p>{@code TrialSpawnerDetectionParticle} builds it as {@code 0.75 *
	 * scale}; the aura supplies the scale per mote, so this is the 0.75.
	 */
	public static final float QUAD_SIZE = 0.75f;

	/**
	 * How sharply the particle grows when it is born.
	 *
	 * <p>Vanilla: {@code quadSize * clamp(age / lifetime * 32, 0, 1)}. At
	 * thirty-two times its life the streak reaches full length in the first
	 * thirtieth of it, so it snaps into being rather than swelling -- which is
	 * what makes it read as a spark struck rather than a bubble rising.
	 */
	private static final float GROWTH = 32.0f;

	private TrialSparks() {
	}

	/** The sprite for a spark that is {@code life} of the way through its rise. */
	public static Identifier sprite(float life) {
		return SPRITES[frame(life)];
	}

	/** That frame's streak length in pixels, for a caller drawing the texture. */
	public static int spriteHeight(float life) {
		return HEIGHTS[frame(life)];
	}

	private static int frame(float life) {
		int frame = (int) (Mth.clamp(life, 0.0f, 1.0f) * FRAMES);
		return Math.min(frame, FRAMES - 1);
	}

	/**
	 * Vanilla's birth growth: 0 at the instant of spawning, 1 once the streak
	 * is at full length, which happens almost at once.
	 */
	public static float growth(float life) {
		return Mth.clamp(life * GROWTH, 0.0f, 1.0f);
	}

	/**
	 * How far the tier colour is pushed towards full saturation.
	 *
	 * <p>A grade colour is picked to be readable as text on a dark plate, not
	 * to glow, so a little deepening keeps a spark from looking washed out.
	 * Enough to give the colour some depth, short of the point where a grade
	 * stops looking like the colour on its own tag.
	 */
	private static final float VIVID = 0.45f;

	/**
	 * How white the head of a spark goes at its hottest.
	 *
	 * <p>Small, and only at the very end of the rise. Vanilla's last frame is
	 * near white, but taking the whole particle there washed the tier colour
	 * out of it -- which is the one thing the aura is for.
	 */
	private static final float HOT = 0.15f;

	/** Where in its life a spark starts to whiten. */
	private static final float HOT_FROM = 0.75f;

	/**
	 * How far every grade is taken towards having a full-strength channel.
	 *
	 * <p>Separate from {@link #VIVID}, which only brightens a colour that has
	 * little hue to deepen. A saturated grade got almost none of that lift, so
	 * the sprite's bands and the blend below it left even the head of a streak
	 * short of full -- lit, but dim. This lifts every grade alike, which is
	 * what a spark being a light source rather than a painted shape means.
	 */
	private static final float GLOW = 0.7f;

	/**
	 * The tier colour as a spark of it, alpha carried through.
	 *
	 * <p>Saturated first so the colour reads as light rather than as paint,
	 * then whitened a little at the very end, the way vanilla's own sprite
	 * brightens as it goes out.
	 */
	public static int colour(int rgb, float life, float alpha) {
		int vivid = saturate(rgb);
		float hot = life <= HOT_FROM
				? 0.0f
				: (life - HOT_FROM) / (1.0f - HOT_FROM) * HOT;
		int lit = ARGB.color(
				Mth.lerpInt(hot, ARGB.red(vivid), 255),
				Mth.lerpInt(hot, ARGB.green(vivid), 255),
				Mth.lerpInt(hot, ARGB.blue(vivid), 255)) & 0xFFFFFF;
		return (Mth.clamp((int) (alpha * 255.0f), 0, 255) << 24) | lit;
	}

	/**
	 * The same hue, at fuller saturation and brightness.
	 *
	 * <p>Two steps. Each channel is pushed away from the colour's own darkest,
	 * which deepens the hue rather than merely lightening it, and the whole is
	 * then brightened towards full.
	 *
	 * <p>Both steps are weighted by how much hue the colour actually has. A
	 * grade like B is already a pure cyan and needs no deepening; the ungraded
	 * grey has almost no hue to deepen, and pushing it as hard turned it a
	 * definite blue -- a colour nothing on the tier list uses. So a colourful
	 * grade is saturated and a drab one is brightened, and neither changes
	 * into something else.
	 */
	private static int saturate(int rgb) {
		int r = ARGB.red(rgb);
		int g = ARGB.green(rgb);
		int b = ARGB.blue(rgb);
		int peak = Math.max(r, Math.max(g, b));
		if (peak <= 0) {
			return 0;
		}
		int floor = Math.min(r, Math.min(g, b));
		// 0 for a grey, 1 for a fully saturated hue.
		float chroma = (peak - floor) / (float) peak;
		float deepen = VIVID * chroma;
		// What is not spent deepening is spent brightening, so every grade
		// gains the same amount of life whichever way it needs it, and then
		// GLOW lifts them all towards full regardless of hue.
		float lift = Mth.lerp(VIVID * (1.0f - chroma), 1.0f, 255.0f / peak)
				* Mth.lerp(GLOW, 1.0f, 255.0f / peak);
		return ARGB.color(
				channel(r, floor, peak, deepen, lift),
				channel(g, floor, peak, deepen, lift),
				channel(b, floor, peak, deepen, lift)) & 0xFFFFFF;
	}

	private static int channel(int value, int floor, int peak, float deepen, float lift) {
		float pure = peak > floor ? (value - floor) * 255.0f / (peak - floor) : value;
		return Mth.clamp((int) (Mth.lerp(deepen, value, pure) * lift), 0, 255);
	}
}
