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
	/** The tierlist's name, as it appears in the tooltip. */
	public static final String LIST_NAME = "Door SMP";

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
	 * The colour to draw the grade in, opaque.
	 *
	 * <p>Falls back to the ladder below if the service sent something
	 * unreadable, so a bad colour is a wrong shade rather than black on black.
	 */
	public int foreground() {
		int rgb = color != 0 ? color : fallbackColor(grade);
		return 0xFF000000 | rgb;
	}

	/**
	 * The tag's fill, the same colour darkened.
	 *
	 * <p>The region tags pair a bright foreground with a dark, desaturated
	 * background of the same hue; this reproduces that from one colour so every
	 * grade sits at the same visual weight as the region tag beside it.
	 */
	public int background() {
		int rgb = color != 0 ? color : fallbackColor(grade);
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
