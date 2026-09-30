package dev.spog.tiers.client;

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
	 * <p>Vanilla's own files, referenced where they already sit rather than
	 * copied into this mod: they ship with the game, and a copy would have to
	 * be kept in step with it by hand.
	 */
	private static final Identifier[] SPRITES = new Identifier[FRAMES];

	static {
		for (int i = 0; i < FRAMES; i++) {
			SPRITES[i] = Identifier.withDefaultNamespace(
					"textures/particle/trial_spawner_detection_ominous_" + i + ".png");
		}
	}

	/**
	 * How wide the streak is against its height, from the sprite: the drawn
	 * column is one pixel of eight across and six tall at its longest.
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

	/** The sprite for a mote that is {@code life} of the way through its rise. */
	public static Identifier sprite(float life) {
		int frame = (int) (Mth.clamp(life, 0.0f, 1.0f) * FRAMES);
		return SPRITES[Math.min(frame, FRAMES - 1)];
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
	 * to glow. Drawn straight it gives a washed-out spark, so the colour is
	 * taken to its most vivid form at the same hue: the grade stays
	 * recognisable and the particle actually burns.
	 */
	private static final float VIVID = 0.55f;

	/**
	 * How white the head of a spark goes at its hottest.
	 *
	 * <p>Small, and only at the very end of the rise. Vanilla's last frame is
	 * near white, but taking the whole particle there washed the tier colour
	 * out of it -- which is the one thing the aura is for.
	 */
	private static final float HOT = 0.3f;

	/** Where in its life a spark starts to whiten. */
	private static final float HOT_FROM = 0.75f;

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
		// gains the same amount of life whichever way it needs it.
		float lift = Mth.lerp(VIVID * (1.0f - chroma), 1.0f, 255.0f / peak);
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
