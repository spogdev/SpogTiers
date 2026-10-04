package dev.spog.tiers.client.gui;

import dev.spog.tiers.client.TrialSparks;
import dev.spog.tiers.client.WorldAura;
import dev.spog.tiers.data.PlayerGrade;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gl.RenderPipelines;

/**
 * Embers drifting up around the skin model, in the colour of the player's Door
 * SMP tier.
 *
 * <p>Drawn as fills rather than real particles: the model is a GUI widget, not
 * a world entity, so there is no particle system to hand. The shape and the
 * colour ramp are {@link TrialSparks}', the same the world aura draws with a
 * real sprite, so the panel shows the effect the player wears.
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

	/**
	 * How long a streak is against the mote size, in pixels.
	 *
	 * <p>The world's own multiplier is in blocks against a different size, so
	 * the two are not the same number; this is the one that makes a spark on
	 * the panel look like the spark on the player.
	 */
	private static final float STREAK = 6.0f;

	/**
	 * How much of the box's height the embers fade out over, at the top.
	 *
	 * <p>In the world an ember just keeps going: it rises past the head,
	 * thins as it cools and leaves the view with nothing to stop it. A panel
	 * has an edge, and above the model sits the player's name and tags, so
	 * the aura has to end somewhere -- clipped, it ended on a hard line just
	 * above the head, which reads as the effect being cut off rather than
	 * burning out.
	 *
	 * <p>A fifth of the box, which is a little more than the {@code 0.18} of
	 * it that {@code up()} spends above the head: the ramp therefore starts
	 * before a mote reaches the top of the model and is complete by the time
	 * it would have left the box, so nothing is ever clipped mid-streak.
	 */
	private static final float FADE_TOP = 0.2f;

	/**
	 * How far above the box a clip has to reach to not cut a visible ember.
	 *
	 * <p>A streak is drawn centred on its position, so its lower half hangs
	 * below the point the fade is measured at: a mote can still be faintly
	 * lit while the bottom of its sprite is past the top of the box. Half the
	 * longest streak is as far as that can reach.
	 *
	 * <p>Callers that clip the aura add this to the top of their scissor, so
	 * the fade is what ends the ember and the clip only ever removes pixels
	 * that are already transparent.
	 */
	public static final int TOP_BLEED = (int) Math.ceil(MAX_SIZE * STREAK / 2.0);

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
	public void draw(DrawContext graphics, PlayerGrade grade,
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

			float fade = topFade(aura.up(i, elapsed));
			if (fade <= 0.0f) {
				continue;
			}

			float life = aura.life(i, elapsed);
			int s = MIN_SIZE + Math.round(aura.size(i, elapsed) * (MAX_SIZE - MIN_SIZE));

			streak(graphics, i, elapsed, left, top, width, height, s,
					TrialSparks.colour(rgb, life, aura.alpha(i, elapsed) * fade));
		}
	}

	/**
	 * How much of an ember survives, by how near the top of the box it is.
	 *
	 * <p>1 over most of the rise, easing to 0 across the top {@link #FADE_TOP}
	 * of the box. Squared, so the ember holds its brightness for most of the
	 * band and then goes quickly: a straight ramp reads as a dimmer being
	 * turned down, where this reads as burning out.
	 *
	 * @param up the mote's height, 0 at the bottom of the box and 1 at the top
	 */
	private static float topFade(float up) {
		float from = 1.0f - FADE_TOP;
		if (up <= from) {
			return 1.0f;
		}
		if (up >= 1.0f) {
			return 0.0f;
		}
		float left = (1.0f - up) / FADE_TOP;
		return left * left;
	}

	/**
	 * One streak, drawn where the mote is at {@code at} seconds.
	 *
	 * <p>A tall thin bar rather than a square, in the proportion the trial
	 * chamber sprite has, and grown the way that particle grows: the panel and
	 * the world then show the same spark, one in pixels and one in blocks.
	 */
	private void streak(DrawContext graphics, int mote, float at,
			int left, int top, int width, int height, int size, int colour) {
		float life = aura.life(mote, at);
		int tall = Math.max(1, Math.round(size * STREAK * TrialSparks.growth(life)));
		int wide = Math.max(1, Math.round(tall * TrialSparks.ASPECT));
		int x = left + Math.round(aura.across(mote, at) * width - wide / 2.0f);
		int y = top + Math.round((1.0f - aura.up(mote, at)) * height - tall / 2.0f);
		// The same sprite the world draws, tinted the same way, rather than a
		// flat bar: the texture is what gives the streak a bright head and a
		// dim tail, and a rectangle of one colour has none of that.
		// Only the streak's own corner of the sheet, not the whole 8x8: the
		// rest is the transparent margin the sprite is drawn on.
		graphics.drawTexture(RenderPipelines.GUI_TEXTURED, TrialSparks.sprite(life),
				x, y, TrialSparks.STREAK_LEFT, TrialSparks.STREAK_TOP, wide, tall,
				TrialSparks.STREAK_WIDTH, TrialSparks.spriteHeight(life),
				TrialSparks.SPRITE_SIZE, TrialSparks.SPRITE_SIZE, colour);
		// The white head over it, as the world draws it: the tint below can
		// only be darkened by its sprite, so the burn at the top has to be
		// added rather than multiplied in.
		graphics.drawTexture(RenderPipelines.GUI_TEXTURED, TrialSparks.hotSprite(life),
				x, y, TrialSparks.STREAK_LEFT, TrialSparks.STREAK_TOP, wide, tall,
				TrialSparks.STREAK_WIDTH, TrialSparks.spriteHeight(life),
				TrialSparks.SPRITE_SIZE, TrialSparks.SPRITE_SIZE, 0xFFFFFFFF);
	}
}
