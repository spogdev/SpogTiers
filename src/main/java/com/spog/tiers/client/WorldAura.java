package com.spog.tiers.client;

import net.minecraft.util.math.MathHelper;

import java.util.Random;

/**
 * The motion of the ember aura, shared by the profile screen and the world.
 *
 * <p>One definition of the effect, so the two renderings cannot drift apart.
 * Everything here is a pure function of elapsed time and each mote's own seed:
 * no per-frame state is kept, nothing accumulates, and the same moment always
 * produces the same layout.
 *
 * <p>Positions come out normalised -- x across the body from 0 to 1, y from 0
 * at the feet to 1 at the head -- so a caller can map them onto a GUI panel or
 * onto a player standing in the world without either knowing about the other.
 */
public final class WorldAura {
	/** Enough to read as a haze; few enough to stay cheap. */
	public static final int MOTES = 46;

	/** Seconds for a mote to travel its full rise. */
	private static final float RISE_SECONDS = 3.4f;

	/** How far a mote wanders sideways over that rise, in body widths. */
	private static final float DRIFT = 0.20f;

	/** How far below the feet motes appear, and above the head they fade out. */
	private static final float BELOW = 0.06f;
	private static final float ABOVE = 0.18f;

	/** Peak opacity. Low on purpose -- this sits around a player. */
	private static final float MAX_ALPHA = 0.78f;

	/** Fixed seed: the drift pattern is the same every time. */
	private static final long SEED = 0x5D0057;

	/**
	 * Share of motes in the front layer rather than behind.
	 *
	 * <p>A minority on purpose. The effect should wrap the player rather than
	 * veil them, so the front layer is thinner, and fainter and smaller too.
	 */
	private static final float FRONT_SHARE = 0.38f;

	/** How much the front layer is toned down, so it never hides the skin. */
	private static final float FRONT_ALPHA_SCALE = 0.62f;

	private final float[] phase = new float[MOTES];
	private final float[] column = new float[MOTES];
	private final float[] wobble = new float[MOTES];
	private final float[] speed = new float[MOTES];
	private final float[] size = new float[MOTES];
	private final boolean[] front = new boolean[MOTES];

	public WorldAura() {
		Random random = new Random(SEED);
		for (int i = 0; i < MOTES; i++) {
			// Phases are spread evenly and then jittered, so motes never leave
			// in a visible pulse the way pure randomness sometimes does.
			phase[i] = (i / (float) MOTES) + (random.nextFloat() - 0.5f) * 0.05f;
			column[i] = random.nextFloat();
			wobble[i] = random.nextFloat() * MathHelper.TAU;
			speed[i] = 0.75f + random.nextFloat() * 0.5f;
			size[i] = random.nextFloat();
			front[i] = random.nextFloat() < FRONT_SHARE;
		}
	}

	/** True when this mote belongs to the layer drawn in front of the player. */
	public boolean isFront(int mote) {
		return front[mote];
	}

	/**
	 * Where a mote is through its rise at {@code elapsed} seconds: 0 at the
	 * feet, 1 above the head.
	 *
	 * <p>Wrapping on 1 means a mote reappears at the bottom rather than needing
	 * to be respawned.
	 */
	public float life(int mote, float elapsed) {
		return (elapsed / (RISE_SECONDS * speed[mote]) + phase[mote]) % 1.0f;
	}

	/**
	 * Opacity from 0 to 1, fading in from nothing and back out so that nothing
	 * pops into or out of existence mid-air.
	 */
	public float alpha(int mote, float elapsed) {
		float fade = MathHelper.sin(life(mote, elapsed) * MathHelper.PI);
		float peak = front[mote] ? MAX_ALPHA * FRONT_ALPHA_SCALE : MAX_ALPHA;
		return (float) Math.sqrt(fade) * peak;
	}

	/**
	 * Position across the body, 0 to 1, or negative when the mote has wandered
	 * outside and should be skipped this frame.
	 */
	public float across(int mote, float elapsed) {
		float life = life(mote, elapsed);
		// Sideways wander, widening as it rises the way smoke spreads.
		float sway = MathHelper.sin(elapsed * 0.9f * speed[mote] + wobble[mote]) * DRIFT * (0.35f + life);
		float x = column[mote] + sway;
		return (x < 0.0f || x > 1.0f) ? -1.0f : x;
	}

	/**
	 * Height up the body: 0 just below the feet, 1 just above the head, so a
	 * mote crosses the whole player instead of expiring beneath them.
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
