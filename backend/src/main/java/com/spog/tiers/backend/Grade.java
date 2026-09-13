package com.spog.tiers.backend;

import java.util.Locale;

/**
 * A Door SMP grade.
 *
 * <p>The colours are the tierlist's own and are the single source of truth for
 * them: the HTTP API serves them to the mod, and the Discord embeds stripe
 * themselves with the same value, so a badge in game and an embed in Discord
 * always agree.
 *
 * <p>Order matters. The constants are declared best to worst, so
 * {@link #ordinal()} sorts a listing without a comparator, and
 * {@link #values()} is already the right order for a Discord choice list.
 */
public enum Grade {
	S("S", 0xFF96F3),
	A_PLUS("A+", 0xA034C7),
	A("A", 0xD42626),
	B_PLUS("B+", 0xEB8526),
	B("B", 0x00F2FF),
	C("C", 0xEDE04E),
	D("D", 0x5F9448),
	F("F", 0x824B27);

	private final String label;
	private final int color;

	Grade(String label, int color) {
		this.label = label;
		this.color = color;
	}

	/** As written on the tierlist: {@code "S"}, {@code "A+"}, and so on. */
	public String label() {
		return label;
	}

	/** The grade's colour as 0xRRGGBB, with no alpha channel. */
	public int color() {
		return color;
	}

	/** The colour as the six uppercase hex digits the API publishes. */
	public String hex() {
		return String.format("%06X", color);
	}

	/**
	 * Parses a label back to a grade, or returns null if it is not one.
	 *
	 * <p>Lenient about case and surrounding whitespace, since this also reads
	 * values back out of a hand-editable JSON file. It is deliberately not
	 * lenient about anything else: an unrecognised grade is an error worth
	 * reporting, not something to guess at.
	 */
	public static Grade parse(String raw) {
		if (raw == null) {
			return null;
		}
		String trimmed = raw.trim().toUpperCase(Locale.ROOT);
		for (Grade grade : values()) {
			if (grade.label.equals(trimmed)) {
				return grade;
			}
		}
		return null;
	}
}
