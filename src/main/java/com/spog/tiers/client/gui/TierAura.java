package com.spog.tiers.client.gui;

import com.spog.tiers.client.WorldAura;
import com.spog.tiers.data.PlayerGrade;
import net.minecraft.client.gui.DrawContext;

/**
 * Embers drifting up around the skin model, in the colour of the player's Door
 * SMP tier.
 *
 * <p>Drawn as small translucent quads rather than real particles: the model is
 * a GUI widget, not a world entity, so there is no particle system to hand.
 *
 * <p>The motion itself lives in {@link WorldAura}, shared with the copy drawn
 * around the player in the world, so the two are the same effect rather than
 * two that merely resemble each other. This class only maps it onto the panel.
 */
public final class TierAura {
	/** Mote size in pixels, before the per-mote variation. */
	private static final int MIN_SIZE = 1;
	private static final int MAX_SIZE = 4;

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

		for (int i = 0; i < WorldAura.MOTES; i++) {
			if (aura.isFront(i) != inFront) {
				continue;
			}
			int alpha = (int) (aura.alpha(i, elapsed) * 255.0f);
			if (alpha <= 2) {
				continue;
			}
			float x = aura.across(i, elapsed);
			if (x < 0.0f) {
				continue;
			}

			int px = left + Math.round(x * width);
			int py = top + Math.round((1.0f - aura.up(i, elapsed)) * height);

			// The front layer is drawn a size smaller, so it never competes
			// with the skin it is decorating.
			int span = inFront ? MAX_SIZE - 1 : MAX_SIZE;
			int s = MIN_SIZE + Math.round(aura.size(i, elapsed) * (span - MIN_SIZE));

			graphics.fill(px, py, px + s, py + s, (alpha << 24) | rgb);
		}
	}
}
