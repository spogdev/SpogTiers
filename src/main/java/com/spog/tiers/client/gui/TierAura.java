package com.spog.tiers.client.gui;

import com.spog.tiers.data.PlayerGrade;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.MathHelper;

import java.util.Random;

/**
 * Embers drifting up around the skin model, in the colour of the player's Door
 * SMP tier.
 *
 * <p>Drawn as small translucent quads rather than real particles: the model is
 * a GUI widget, not a world entity, so there is no particle system to hand.
 * They are laid out behind the model, which keeps the player readable -- an
 * effect in front of the skin would obscure the thing it is decorating.
 *
 * <p>Motion is a pure function of elapsed time and each mote's own seed, so no
 * per-frame state is kept and nothing accumulates or drifts out of sync when
 * the screen is resized or the panel re-laid out.
 */
public final class TierAura {
	/** Enough to read as a haze; few enough to stay cheap on the GUI path. */
	private static final int MOTES = 46;

	/** Seconds for a mote to travel its full rise. */
	private static final float RISE_SECONDS = 3.4f;

	/** How far a mote wanders sideways over that rise, in model widths. */
	private static final float DRIFT = 0.20f;

	/** Mote size in pixels, before the per-mote variation. */
	private static final int MIN_SIZE = 1;
	private static final int MAX_SIZE = 4;

	/** How far below the feet motes appear, and above the head they fade out. */
	private static final float BELOW = 0.06f;
	private static final float ABOVE = 0.18f;

	/** Peak opacity. Low on purpose -- this sits behind a player model. */
	private static final float MAX_ALPHA = 0.78f;

	/** Fixed seed: the drift pattern should be the same every time it opens. */
	private static final long SEED = 0x5D0057;

	/**
	 * Share of motes drawn in front of the model rather than behind it.
	 *
	 * <p>A minority on purpose. The effect should wrap the player rather than
	 * veil them, and anything in front competes with the skin for attention --
	 * so the front layer is thinner, and drawn smaller and fainter besides.
	 */
	private static final float FRONT_SHARE = 0.38f;

	/** How much the front layer is toned down, so it never hides the skin. */
	private static final float FRONT_ALPHA_SCALE = 0.62f;

	private final float[] phase = new float[MOTES];
	private final float[] column = new float[MOTES];
	private final float[] wobble = new float[MOTES];
	private final float[] speed = new float[MOTES];
	private final float[] size = new float[MOTES];
	/** Which layer each mote belongs to, fixed at construction. */
	private final boolean[] front = new boolean[MOTES];

	private float elapsed;

	public TierAura() {
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

	/** Advances the animation. Call once per frame before drawing. */
	public void tick(float partialTick) {
		elapsed += partialTick / 20.0f;
	}

	/**
	 * Draws one layer of the aura in the given box, which should be the
	 * model's own bounds.
	 *
	 * <p>Called twice a frame: once before the model renders and once after,
	 * so motes pass both behind and in front of the player and the effect
	 * reads as surrounding them rather than sitting flat behind.
	 *
	 * <p>Does nothing for a player with no tier, so an ungraded profile looks
	 * exactly as it did before.
	 *
	 * @param inFront which layer to draw
	 */
	public void draw(DrawContext graphics, PlayerGrade grade,
			int left, int top, int width, int height, boolean inFront) {
		if (grade == null || !grade.isGraded() || width <= 0 || height <= 0) {
			return;
		}
		int rgb = grade.foreground() & 0xFFFFFF;

		for (int i = 0; i < MOTES; i++) {
			if (front[i] != inFront) {
				continue;
			}
			// Where this mote is through its rise, 0 at the feet and 1 at the
			// top. Wrapping on 1 means it reappears at the bottom rather than
			// needing to be respawned.
			float life = (elapsed / (RISE_SECONDS * speed[i]) + phase[i]) % 1.0f;

			// Fades in from nothing and back out, so nothing pops into or out
			// of existence mid-air.
			float fade = MathHelper.sin(life * MathHelper.PI);
			float peak = inFront ? MAX_ALPHA * FRONT_ALPHA_SCALE : MAX_ALPHA;
			int alpha = (int) (Math.sqrt(fade) * peak * 255.0f);
			if (alpha <= 2) {
				continue;
			}

			// Sideways wander, widening as it rises the way smoke spreads.
			float sway = MathHelper.sin(elapsed * 0.9f * speed[i] + wobble[i]) * DRIFT * (0.35f + life);
			float x = column[i] + sway;
			if (x < 0.0f || x > 1.0f) {
				continue;
			}

			int px = left + Math.round(x * width);
			// life 0 is just below the feet, life 1 just above the head, so a
			// mote crosses the whole model instead of expiring beneath it.
			float travel = (1.0f + BELOW + ABOVE) * life - BELOW;
			int py = top + Math.round((1.0f - travel) * height);

			// Larger near the bottom, thinning as they climb -- embers cooling.
			int span = inFront ? MAX_SIZE - 1 : MAX_SIZE;
			int s = MIN_SIZE + Math.round(size[i] * (span - MIN_SIZE) * (1.0f - life * 0.6f));

			graphics.fill(px, py, px + s, py + s, (alpha << 24) | rgb);
		}
	}
}
