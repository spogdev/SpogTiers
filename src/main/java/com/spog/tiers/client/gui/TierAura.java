package com.spog.tiers.client.gui;

import com.spog.tiers.client.WorldAura;
import com.spog.tiers.data.PlayerGrade;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Embers drifting up around the skin model, in the colour of the player's Door
 * SMP tier.
 *
 * <p>Drawn as small fills rather than real particles: the model is a GUI
 * widget, not a world entity, so there is no particle system to hand.
 *
 * <p>The ember itself lives in {@link WorldAura} -- its motion, its parts and
 * their colours -- shared with the copy drawn around the player in the world,
 * so the two are the same effect rather than two that merely resemble each
 * other. This class only maps it onto the panel in pixels.
 */
public final class TierAura {
	/** Core size in pixels, before the per-ember variation. */
	private static final int MIN_SIZE = 1;
	private static final int MAX_SIZE = 4;

	/** How far the glow, and the halo beyond it, reach past the ember's body, in pixels. */
	private static final int GLOW = 1;
	private static final int HALO = 2;

	/**
	 * How much of the ember's body the lit core takes.
	 *
	 * <p>Under half, so the core is a bright point sitting inside the glow
	 * rather than the whole ember: a glow needs something to be glowing around
	 * to read as one.
	 */
	private static final float CORE_SHARE = 0.45f;

	private final WorldAura aura = new WorldAura();

	private float elapsed;

	/** Advances the animation. Call once per frame before drawing. */
	public void tick(float partialTick) {
		elapsed += partialTick / 20.0f;
	}

	/**
	 * Draws one layer of the aura in the given box, which should be the
	 * model's own bounds.
	 *
	 * <p>Called twice a frame: once before the model renders and once after,
	 * so embers pass both behind and in front of the player and the effect
	 * reads as surrounding them rather than sitting flat behind.
	 *
	 * <p>Does nothing for a player with no tier, so an ungraded profile looks
	 * exactly as it did before.
	 *
	 * @param inFront which layer to draw
	 */
	public void draw(GuiGraphicsExtractor graphics, PlayerGrade grade,
			int left, int top, int width, int height, boolean inFront) {
		if (grade == null || !grade.isGraded() || width <= 0 || height <= 0) {
			return;
		}
		int rgb = grade.foreground() & 0xFFFFFF;

		for (int i = 0; i < WorldAura.MOTES; i++) {
			if ((aura.depth(i, elapsed) > 0.0f) != inFront) {
				continue;
			}
			if (aura.alpha(i, elapsed) * 255.0f <= 2.0f) {
				continue;
			}

			int s = MIN_SIZE + Math.round(aura.size(i, elapsed) * (MAX_SIZE - MIN_SIZE));
			int px = pixelX(i, elapsed, left, width, s);
			int py = pixelY(i, elapsed, top, height, s);

			// Halo, then glow, then the tail, then the core over all of them:
			// the parts overlap, and the brightest must land on top.
			graphics.fill(px - HALO, py - HALO, px + s + HALO, py + s + HALO,
					aura.haloColour(rgb, i, elapsed));
			graphics.fill(px - GLOW, py - GLOW, px + s + GLOW, py + s + GLOW,
					aura.glowColour(rgb, i, elapsed));

			// Each tail step is a smaller, fainter core where the ember was a
			// moment ago, so the tail bends along the path it took. Furthest
			// first, so nearer steps land on top.
			for (int segment = WorldAura.TAIL_SEGMENTS - 1; segment >= 0; segment--) {
				if (!aura.tailVisible(i, elapsed, segment)) {
					continue;
				}
				float then = aura.tailTime(elapsed, segment);
				int ts = Math.max(1, Math.round(s * aura.tailSize(segment)));
				int tx = pixelX(i, then, left, width, ts);
				int ty = pixelY(i, then, top, height, ts);
				graphics.fill(tx, ty, tx + ts, ty + ts, aura.tailColour(rgb, i, elapsed, segment));
			}

			// The body in the glow colour, then the lit centre inside it: the
			// core is a bright point the glow surrounds, not the whole ember.
			graphics.fill(px, py, px + s, py + s, aura.glowColour(rgb, i, elapsed));
			int core = Math.max(1, Math.round(s * CORE_SHARE));
			int inset = (s - core) / 2;
			graphics.fill(px + inset, py + inset, px + inset + core, py + inset + core,
					aura.coreColour(rgb, i, elapsed));
		}
	}

	/** Left edge of a square of {@code size} centred on the ember at {@code at} seconds. */
	private int pixelX(int mote, float at, int left, int width, int size) {
		return left + Math.round(aura.across(mote, at) * width - size / 2.0f);
	}

	/** Top edge of a square of {@code size} centred on the ember at {@code at} seconds. */
	private int pixelY(int mote, float at, int top, int height, int size) {
		return top + Math.round((1.0f - aura.up(mote, at)) * height - size / 2.0f);
	}
}
