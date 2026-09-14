package dev.spog.tiers.client.gui;

/**
 * Where a tooltip goes.
 *
 * <p>Every tooltip in the mod is drawn by its own screen, in its own palette
 * and at its own size, but they all want to sit in the same relation to the
 * pointer. Only that decision lives here, so a tooltip that has run out of
 * room behaves the same way wherever it appears.
 */
public final class Tooltips {
	/** The gap between the pointer and the box, on whichever side it lands. */
	private static final int GAP = 12;

	/** How close to the screen edge a box may sit. */
	private static final int MARGIN = 4;

	private Tooltips() {
	}

	/**
	 * The x a tooltip of this width should be drawn at.
	 *
	 * <p>To the right of the pointer while it fits there, and to the left
	 * when it does not. Clamping it against the right edge instead -- which
	 * is what this replaces -- slid the box back under the pointer, so the
	 * thing being explained ended up beneath the explanation.
	 *
	 * <p>When neither side fits, which needs a box wider than the screen has
	 * room for on either side of the pointer, the side with more space wins
	 * and the box is clamped into it. That is the one case where the box can
	 * still reach under the pointer, and there is nowhere else for it to go.
	 *
	 * @param mouseX where the pointer is
	 * @param boxWidth the width of the box about to be drawn
	 * @param screenWidth the width of the screen it is drawn on
	 */
	public static int x(int mouseX, int boxWidth, int screenWidth) {
		int right = mouseX + GAP;
		if (right + boxWidth + MARGIN <= screenWidth) {
			return right;
		}
		int left = mouseX - GAP - boxWidth;
		if (left >= MARGIN) {
			return left;
		}
		// Neither side has room. Take the wider one and sit against its edge.
		return mouseX > screenWidth / 2
				? MARGIN
				: Math.max(MARGIN, screenWidth - boxWidth - MARGIN);
	}
}
