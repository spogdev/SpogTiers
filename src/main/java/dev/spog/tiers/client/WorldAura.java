package dev.spog.tiers.client;

import net.minecraft.util.Mth;

import java.util.Random;

/**
 * The motion of the spark aura, shared by the profile screen and the world.
 *
 * <p>One definition of the effect, so the two renderings cannot drift apart.
 * Everything here is a pure function of elapsed time and each ember's own
 * seed: no per-frame state is kept, nothing accumulates, and the same moment
 * always produces the same layout.
 *
 * <p>Sparks rise on the player's own axis: each has an angle around the body
 * and a distance from its centre, and climbs straight up from there.
 * Positions come out normalised -- {@linkplain #across x across the body} from 0 to 1,
 * {@linkplain #up y} from 0 at the feet to 1 at the head, {@linkplain #depth
 * depth} from -1 behind to 1 in front -- so a caller can map them onto a GUI
 * panel or onto a player standing in the world without either knowing about
 * the other.
 *
 * <p>Colour and shape are not here: {@link TrialSparks} owns those, so this
 * class says only where a mote is, how big and how bright, and the two
 * renderings share both halves.
 */
public final class WorldAura {
	/** Enough to read as a haze; few enough to stay cheap. */
	public static final int MOTES = 46;

	/** Seconds for an ember to travel its full rise. */
	private static final float RISE_SECONDS = 3.4f;

	/**
	 * How far round the body an ember wanders over its rise, in radians.
	 *
	 * <p>Zero: a trial chamber spark goes straight up. The wander was right
	 * for embers coming off a fire, which curl as the air moves them, but this
	 * particle is struck and rises, and a spark that slides sideways as it
	 * climbs reads as drifting smoke instead.
	 */
	private static final float DRIFT = 0.0f;

	/** A finer, faster jitter on top of the wander. Zero, as {@link #DRIFT} is. */
	private static final float JITTER = 0.0f;

	/** How close to and far from the body's centre embers sit, 1 being the edge. */
	private static final float NEAREST = 0.5f;
	private static final float FARTHEST = 1.0f;

	/**
	 * How far in and out an ember breathes from its own distance over time.
	 *
	 * <p>Zero, with {@link #DRIFT}: breathing moves a spark across the screen
	 * as surely as swaying does, just more slowly.
	 */
	private static final float BREATH = 0.0f;

	/**
	 * How much further out an ember drifts by the top of its rise.
	 *
	 * <p>Zero, so a spark keeps the distance it was born at and its path is a
	 * vertical line. Kept as a named constant rather than deleted: the column
	 * is a deliberate shape, and this is where it would be widened again.
	 */
	private static final float WIDENING = 0.0f;

	/** How far below the feet embers appear, and above the head they fade out. */
	private static final float BELOW = 0.06f;
	private static final float ABOVE = 0.18f;

	/**
	 * Peak opacity.
	 *
	 * <p>One, with the two floors below: sparks are drawn solid. They are a
	 * light, and a light is either burning or gone -- fading one in and out
	 * only let the ground show through and mix into its colour.
	 */
	private static final float MAX_ALPHA = 1.0f;

	/**
	 * How opaque a spark stays at its faintest.
	 *
	 * <p>One as well, so nothing fades. A spark still visibly dies without
	 * it: the streak shortens as it rises, and the sprite it is drawn from
	 * shortens with it.
	 */
	private static final float MIN_ALPHA = 1.0f;

	/**
	 * Flicker depth: alpha swings between this and one.
	 *
	 * <p>One: no flicker. It multiplied the alpha, so any dip made a spark
	 * part-transparent and let the ground mix into its colour.
	 */
	private static final float FLICKER_FLOOR = 1.0f;

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
		float swell = (float) Math.sqrt(fade) * MAX_ALPHA;
		return (MIN_ALPHA + (1.0f - MIN_ALPHA) * swell) * flicker(mote, elapsed);
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

}
