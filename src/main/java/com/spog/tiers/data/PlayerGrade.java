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
	/** The plate a badge is drawn on: the nameplate backdrop. */
	private static final int PLATE = 0x1E2126;

	/** The contrast a badge must reach against it, and how it gets there. */
	private static final double MIN_CONTRAST = 4.5;
	private static final float LIFT_STEP = 0.02f;
	private static final int MAX_LIFT_STEPS = 50;

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
		return 0xFF000000 | readable(tint());
	}

	/**
	 * The colour lightened just enough to read on the tag.
	 *
	 * <p>The tierlist picks its colours to look right as blocks on the
	 * rendered tierlist, where they are filled areas with dark text on them.
	 * The mod draws them the other way round -- as small text on a dark plate
	 * -- and the darkest of them (F, A, A+) land near 2.5:1 that way, which is
	 * not readable at nameplate size.
	 *
	 * <p>So a colour is blended towards white only until it clears the 4.5:1
	 * the guidelines ask for, and colours that already pass are left exactly
	 * as the tierlist set them. The hue is kept: an F still reads as the same
	 * brown, just light enough to see.
	 */
	private static int readable(int rgb) {
		int lifted = rgb;
		// A bounded walk rather than solving for the blend: the steps are
		// small, it stops at the first that passes, and it cannot loop.
		for (int step = 0; step <= MAX_LIFT_STEPS; step++) {
			if (contrast(lifted, PLATE) >= MIN_CONTRAST) {
				return lifted;
			}
			lifted = blend(rgb, 0xFFFFFF, step * LIFT_STEP);
		}
		return lifted;
	}

	/** WCAG relative luminance, for {@link #contrast}. */
	private static double luminance(int rgb) {
		return 0.2126 * linear((rgb >> 16) & 0xFF)
				+ 0.7152 * linear((rgb >> 8) & 0xFF)
				+ 0.0722 * linear(rgb & 0xFF);
	}

	/** One sRGB channel, 0-255, linearised for the luminance sum. */
	private static double linear(int value) {
		double v = value / 255.0;
		return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
	}

	/** The WCAG contrast ratio between two opaque colours. */
	private static double contrast(int a, int b) {
		double first = luminance(a);
		double second = luminance(b);
		double lighter = Math.max(first, second);
		double darker = Math.min(first, second);
		return (lighter + 0.05) / (darker + 0.05);
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
			case "S" -> 0xFF96F3;
			case "A+" -> 0xA034C7;
			case "A" -> 0xD42626;
			case "B+" -> 0xEB8526;
			case "B" -> 0x00F2FF;
			case "C" -> 0xEDE04E;
			case "D" -> 0x5F9448;
			case "F" -> 0x824B27;
			default -> 0xB9C4D0;
		};
	}
}
