package com.spog.tiers.data;

import java.util.Locale;

/**
 * A player's Door SMP grade: our own tierlist, rather than one of the six
 * third-party lists the rest of this package reads.
 *
 * <p>It is a single letter with no gamemode dimension, so it is not a
 * {@link TierList} and has no card -- it draws as one small tag beside the
 * region on the profile screen.
 *
 * <p>{@link #UNGRADED} is a real value rather than null. Most players have no
 * grade, and the difference between "we asked and they have none" and "we have
 * not asked yet" is what stops the screen refetching every frame.
 */
public record PlayerGrade(String grade, int color, long gradedAt, boolean retired) {
	/** How far a retired tier's colour is pulled towards grey. */
	private static final float RETIRED_FADE = 0.55f;

	/** A definite answer that this player has no grade. */
	public static final PlayerGrade UNGRADED = new PlayerGrade("", 0, 0L, false);

	/** Whether there is a grade to draw. */
	public boolean isGraded() {
		return !grade.isEmpty();
	}

	/**
	 * The label on the badge.
	 *
	 * <p>A retired player keeps their tier, prefixed with R, so the tag says
	 * both what they reached and that they are no longer active.
	 */
	public String label() {
		return retired ? "R" + grade : grade;
	}

	/**
	 * The grade as the tooltip says it.
	 *
	 * <p>Retirement is written out rather than left as the badge's R: the badge
	 * has one letter of room and the tooltip is where the abbreviation gets
	 * explained.
	 */
	public String tooltipLabel() {
		return retired ? "Retired " + grade : grade;
	}

	/**
	 * The colour to draw the grade in, opaque.
	 *
	 * <p>Falls back to the ladder below if the service sent something
	 * unreadable, so a bad colour is a wrong shade rather than black on black.
	 */
	public int foreground() {
		return 0xFF000000 | tint();
	}

	/**
	 * The tier's colour, washed out when the player is retired.
	 *
	 * <p>Retirement keeps the tier's identity -- an RS still reads as an S --
	 * so the colour is desaturated rather than replaced. It is pulled towards a
	 * mid grey rather than towards white: these are already light pastels, and
	 * lightening them further would run them all together.
	 */
	private int tint() {
		int rgb = color != 0 ? color : fallbackColor(grade);
		if (!retired) {
			return rgb;
		}
		return blend(rgb, 0x8A8A8A, RETIRED_FADE);
	}

	/** Mixes {@code towards} into {@code rgb} by the given fraction. */
	private static int blend(int rgb, int towards, float amount) {
		int r = channel((rgb >> 16) & 0xFF, (towards >> 16) & 0xFF, amount);
		int g = channel((rgb >> 8) & 0xFF, (towards >> 8) & 0xFF, amount);
		int b = channel(rgb & 0xFF, towards & 0xFF, amount);
		return (r << 16) | (g << 8) | b;
	}

	private static int channel(int from, int to, float amount) {
		return Math.round(from + (to - from) * amount);
	}

	/**
	 * The tag's fill, the same colour darkened.
	 *
	 * <p>The region tags pair a bright foreground with a dark, desaturated
	 * background of the same hue; this reproduces that from one colour so every
	 * grade sits at the same visual weight as the region tag beside it.
	 */
	public int background() {
		int rgb = tint();
		int r = ((rgb >> 16) & 0xFF) * 3 / 10;
		int g = ((rgb >> 8) & 0xFF) * 3 / 10;
		int b = (rgb & 0xFF) * 3 / 10;
		return 0xE0000000 | (r << 16) | (g << 8) | b;
	}

	/**
	 * The published colour for a grade.
	 *
	 * <p>The service is the source of truth and sends these with every answer;
	 * this only covers the case where that field is missing or malformed.
	 */
	private static int fallbackColor(String grade) {
		return switch (grade.toUpperCase(Locale.ROOT)) {
			case "S" -> 0xFF7FFF;
			case "A+" -> 0xFFBF7F;
			case "A" -> 0xFFDF7F;
			case "B+" -> 0xFFFF7F;
			case "B" -> 0xBFFF7F;
			case "C" -> 0x7FFF7F;
			case "D" -> 0x7FFFFF;
			case "F" -> 0x7F7FFF;
			default -> 0xB9C4D0;
		};
	}
}
