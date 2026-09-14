package dev.spog.tiers.client;

import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;

import java.util.Random;

/**
 * The ember aura, shared by the profile screen and the world.
 *
 * <p>One definition of the effect, so the two renderings cannot drift apart.
 * Everything here is a pure function of elapsed time and each ember's own
 * seed: no per-frame state is kept, nothing accumulates, and the same moment
 * always produces the same layout.
 *
 * <p>Embers orbit the player's own axis: each has an angle around the body
 * and a distance from its centre, and rises while wandering round. Positions
 * come out normalised -- {@linkplain #across x across the body} from 0 to 1,
 * {@linkplain #up y} from 0 at the feet to 1 at the head, {@linkplain #depth
 * depth} from -1 behind to 1 in front -- so a caller can map them onto a GUI
 * panel or onto a player standing in the world without either knowing about
 * the other.
 *
 * <p>Each ember is drawn in three parts, and this class supplies the colour
 * of each so the two callers only place geometry:
 * <ul>
 * <li>a {@linkplain #coreColour core}: a hot, near-white point when the ember
 * is born, cooling to the tier colour as it climbs and dimming as it dies;</li>
 * <li>a {@linkplain #glowColour glow}: a faint cast of the tier colour one
 * step around the core, and a fainter {@linkplain #haloColour halo} a step
 * beyond that;</li>
 * <li>a {@linkplain #tailColour tail}: {@value #TAIL_SEGMENTS} fainter, smaller
 * copies of the core at the places the ember was a moment ago, so it trails a
 * wisp along the path it actually took, bending as the ember wanders.</li>
 * </ul>
 * The whole ember flickers, the way a real one does as it burns.
 */
public final class WorldAura {
	/** Enough to read as a haze; few enough to stay cheap. */
	public static final int MOTES = 46;

	/** Tail steps behind the core, nearest first. */
	public static final int TAIL_SEGMENTS = 4;

	/** Seconds between one tail step and the next. */
	private static final float TAIL_STEP = 0.045f;

	/** Each tail step's size and opacity, as a share of the core's. */
	private static final float[] TAIL_SIZE = {0.85f, 0.7f, 0.5f, 0.35f};
	private static final float[] TAIL_ALPHA = {0.55f, 0.38f, 0.22f, 0.10f};

	/** Seconds for an ember to travel its full rise. */
	private static final float RISE_SECONDS = 3.4f;

	/** How far round the body an ember wanders over its rise, in radians. */
	private static final float DRIFT = 0.55f;

	/** A finer, faster jitter on top of the wander: sparks do not glide. */
	private static final float JITTER = 0.06f;

	/** How close to and far from the body's centre embers sit, 1 being the edge. */
	private static final float NEAREST = 0.5f;
	private static final float FARTHEST = 1.0f;

	/** How far in and out an ember breathes from its own distance over time. */
	private static final float BREATH = 0.12f;

	/** How much further out an ember drifts by the top of its rise. */
	private static final float WIDENING = 0.2f;

	/** How far below the feet embers appear, and above the head they fade out. */
	private static final float BELOW = 0.06f;
	private static final float ABOVE = 0.18f;

	/** Peak opacity. */
	private static final float MAX_ALPHA = 0.95f;

	/** Opacity of the glow round the core and of the wider halo round that, as shares of the core's. */
	private static final float GLOW_ALPHA = 0.6f;
	private static final float HALO_ALPHA = 0.28f;

	/**
	 * How solid the core stays regardless of its own fade.
	 *
	 * <p>The core is the lit point of the ember, and a light source does not
	 * thin out as it travels -- it is either burning or it is gone. Fading it
	 * on the same curve as its glow left the whole thing washed out, so the
	 * core keeps most of its opacity across its life and the surrounding
	 * layers do the fading instead.
	 */
	private static final float CORE_SOLIDITY = 0.72f;

	/** How far a newborn core is pushed towards white. */
	private static final float HOT = 0.75f;

	/**
	 * How white the core stays once it is no longer newborn.
	 *
	 * <p>A hot centre inside a tier-coloured glow, rather than a uniformly
	 * tier-coloured blob: this is what reads as something burning rather than
	 * as a coloured dot.
	 */
	private static final float CORE_WHITE = 0.35f;

	/** Where in its rise an ember starts to darken, and how dark it gets. */
	private static final float COOL_FROM = 0.55f;
	private static final float COOLED = 0.45f;

	/** Flicker depth: alpha swings between this and one. */
	private static final float FLICKER_FLOOR = 0.7f;

	/** Fixed seed: the drift pattern is the same every time. */
	private static final long SEED = 0x5D0057;

	private final float[] phase = new float[MOTES];
	private final float[] angle = new float[MOTES];
	private final float[] radius = new float[MOTES];
	private final float[] wobble = new float[MOTES];
	private final float[] speed = new float[MOTES];
	private final float[] size = new float[MOTES];
	private final float[] flickerRate = new float[MOTES];

	public WorldAura() {
		Random random = new Random(SEED);
		for (int i = 0; i < MOTES; i++) {
			// Phases are spread evenly and then jittered, so embers never leave
			// in a visible pulse the way pure randomness sometimes does.
			phase[i] = (i / (float) MOTES) + (random.nextFloat() - 0.5f) * 0.05f;
			// Likewise round the body: evenly dealt, then nudged, so no side
			// ends up bare while another is crowded.
			angle[i] = (i * Mth.TWO_PI / MOTES) + (random.nextFloat() - 0.5f) * 0.6f;
			radius[i] = NEAREST + random.nextFloat() * (FARTHEST - NEAREST);
			wobble[i] = random.nextFloat() * Mth.TWO_PI;
			speed[i] = 0.75f + random.nextFloat() * 0.5f;
			size[i] = random.nextFloat();
			// Radians per second. Fast enough to read as burning, slow enough
			// not to strobe, and different per ember so they never pulse together.
			flickerRate[i] = 9.0f + random.nextFloat() * 8.0f;
		}
	}

	/**
	 * Where an ember is through its rise at {@code elapsed} seconds: 0 at the
	 * feet, 1 above the head.
	 *
	 * <p>Wrapping on 1 means an ember reappears at the bottom rather than
	 * needing to be respawned.
	 */
	public float life(int mote, float elapsed) {
		float life = (elapsed / (RISE_SECONDS * speed[mote]) + phase[mote]) % 1.0f;
		return life < 0.0f ? life + 1.0f : life;
	}

	/**
	 * Opacity from 0 to 1, fading in from nothing and back out so that nothing
	 * pops into or out of existence mid-air, with the flicker applied.
	 */
	public float alpha(int mote, float elapsed) {
		float fade = Mth.sin(life(mote, elapsed) * Mth.PI);
		return (float) Math.sqrt(fade) * MAX_ALPHA * flicker(mote, elapsed);
	}

	/** The burn, from {@value #FLICKER_FLOOR} to 1, never steady. */
	public float flicker(int mote, float elapsed) {
		float wave = Mth.sin(elapsed * flickerRate[mote] + wobble[mote]);
		return FLICKER_FLOOR + (1.0f - FLICKER_FLOOR) * (0.5f + 0.5f * wave);
	}

	/**
	 * The ember's angle round the body's axis, in radians: 0 is the body's
	 * right, a quarter turn is straight in front.
	 */
	public float theta(int mote, float elapsed) {
		float life = life(mote, elapsed);
		// A wander round the body, widening as it rises the way smoke spreads.
		float sway = Mth.sin(elapsed * 0.9f * speed[mote] + wobble[mote]) * DRIFT * (0.35f + life);
		float jitter = Mth.sin(elapsed * 7.0f * speed[mote] + wobble[mote] * 3.0f) * JITTER;
		return angle[mote] + sway + jitter;
	}

	/**
	 * Distance from the body's axis, 1 being the edge of the aura.
	 *
	 * <p>Each ember has its own distance, drifts outward as it rises, and
	 * breathes slowly in and out on top, so the aura never settles into a
	 * ring at one radius.
	 */
	public float radius(int mote, float elapsed) {
		float breath = Mth.sin(elapsed * 0.8f * speed[mote] + wobble[mote] * 2.0f) * BREATH;
		return radius[mote] * (1.0f + WIDENING * life(mote, elapsed)) * (1.0f + breath);
	}

	/** Position across the body, 0 at the left edge of the aura to 1 at the right. */
	public float across(int mote, float elapsed) {
		return 0.5f + 0.5f * radius(mote, elapsed) * Mth.cos(theta(mote, elapsed));
	}

	/** Position through the body, -1 at the back of the aura to 1 at the front. */
	public float depth(int mote, float elapsed) {
		return radius(mote, elapsed) * Mth.sin(theta(mote, elapsed));
	}

	/**
	 * Height up the body: 0 just below the feet, 1 just above the head, so an
	 * ember crosses the whole player instead of expiring beneath them.
	 */
	public float up(int mote, float elapsed) {
		return (1.0f + BELOW + ABOVE) * life(mote, elapsed) - BELOW;
	}

	/**
	 * Relative size from 0 to 1: larger near the bottom, thinning as they
	 * climb -- embers cooling.
	 */
	public float size(int mote, float elapsed) {
		return size[mote] * (1.0f - life(mote, elapsed) * 0.6f);
	}

	/**
	 * The moment a tail step shows the ember at: {@code segment} steps back
	 * from {@code elapsed}. Position that step with the ordinary position
	 * functions at this time, and it lands where the ember was.
	 */
	public float tailTime(float elapsed, int segment) {
		return elapsed - TAIL_STEP * (segment + 1);
	}

	/**
	 * False when a tail step would reach back past the ember's birth, into its
	 * previous life at the top of the body.
	 */
	public boolean tailVisible(int mote, float elapsed, int segment) {
		return life(mote, tailTime(elapsed, segment)) < life(mote, elapsed);
	}

	/** A tail step's size, as a share of the core's. */
	public float tailSize(int segment) {
		return TAIL_SIZE[segment];
	}

	/**
	 * How hot the ember is, 1 when it is born and 0 by the end of its rise.
	 * Drops off quickly: a spark is white for a moment and coloured for the
	 * rest of its life.
	 */
	public float heat(int mote, float elapsed) {
		float cold = life(mote, elapsed);
		return (1.0f - cold) * (1.0f - cold) * (1.0f - cold);
	}

	/**
	 * The core's colour, alpha included, for a tier colour {@code rgb}.
	 *
	 * <p>Pushed towards white by its heat, then, once past
	 * {@value #COOL_FROM} of its rise, darkened towards a dying ember.
	 */
	public int coreColour(int rgb, int mote, float elapsed) {
		// Never darkened, unlike the glow and the tail: cooling a light source
		// towards black is what made the embers look like dirty smudges late
		// in their rise instead of dimming cleanly.
		float white = CORE_WHITE + (HOT - CORE_WHITE) * heat(mote, elapsed);
		int colour = mix(rgb, 0xFFFFFF, white);
		// Lifted towards opaque so the centre stays a definite point of light
		// even as the ember as a whole fades out.
		float alpha = alpha(mote, elapsed);
		return withAlpha(colour, alpha + (1.0f - alpha) * CORE_SOLIDITY);
	}

	/** The glow around the core: the tier colour, faint. */
	public int glowColour(int rgb, int mote, float elapsed) {
		return withAlpha(rgb, alpha(mote, elapsed) * GLOW_ALPHA);
	}

	/** The wider halo around the glow: the tier colour, fainter still. */
	public int haloColour(int rgb, int mote, float elapsed) {
		return withAlpha(rgb, alpha(mote, elapsed) * HALO_ALPHA);
	}

	/**
	 * One step of the tail, nearest the core first: the tier colour, fainter
	 * with each step, and cooled the same way the core is.
	 */
	public int tailColour(int rgb, int mote, float elapsed, int segment) {
		int colour = mix(rgb, 0x000000, COOLED * cool(mote, elapsed));
		return withAlpha(colour, alpha(mote, elapsed) * TAIL_ALPHA[segment]);
	}

	/** How far into its dying the ember is: 0 until {@value #COOL_FROM}, 1 at the top. */
	private float cool(int mote, float elapsed) {
		float life = life(mote, elapsed);
		return life <= COOL_FROM ? 0.0f : (life - COOL_FROM) / (1.0f - COOL_FROM);
	}

	/** {@code from} blended {@code amount} of the way to {@code to}, per channel. */
	private static int mix(int from, int to, float amount) {
		return ARGB.color(
				Mth.lerpInt(amount, ARGB.red(from), ARGB.red(to)),
				Mth.lerpInt(amount, ARGB.green(from), ARGB.green(to)),
				Mth.lerpInt(amount, ARGB.blue(from), ARGB.blue(to))) & 0xFFFFFF;
	}

	private static int withAlpha(int rgb, float alpha) {
		return (Mth.clamp((int) (alpha * 255.0f), 0, 255) << 24) | (rgb & 0xFFFFFF);
	}
}
