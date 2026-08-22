package com.spog.tiers.data;

/**
 * The extra per-ranking facts the tooltip shows, kept apart from {@link Tier}
 * so the tier itself stays a small value used for colouring and labels.
 *
 * <p>The two families of tier list report different things: the ranked lists
 * date a placement ({@code attainedSeconds}), while ELO-based ones report a
 * rating and how far through the current tier it sits.
 */
public record TierDetail(
		long attainedSeconds,
		int rating,
		int peakRating,
		int tierFloor,
		int tierCeiling,
		String nextTier,
		int peakPoints,
		Tier peak) {

	public static final TierDetail EMPTY =
			new TierDetail(0L, 0, 0, 0, 0, "", 0, null);

	/**
	 * True when the list reports tier points rather than a raw rating. PVPHQ
	 * shows "TP" once a player has them and plain Elo before that.
	 */
	public boolean hasTr() {
		return peakPoints > 0;
	}

	/** True when this came from an ELO-based list and carries a rating. */
	public boolean hasRating() {
		return rating > 0;
	}

	public boolean hasAttained() {
		return attainedSeconds > 0L;
	}

	/**
	 * Progress through the current tier, 0-1.
	 *
	 * <p>Falls back to a full bar once the rating passes the ceiling, which is
	 * how a player sitting at the top of a tier reads on the site.
	 */
	public float progress() {
		if (!hasRating() || tierCeiling <= tierFloor) {
			return 0.0f;
		}
		float span = tierCeiling - tierFloor;
		return Math.clamp((rating - tierFloor) / span, 0.0f, 1.0f);
	}

	/**
	 * Rating earned inside the current tier, rather than the running total.
	 *
	 * <p>Clamped to the tier's span: a player at the top of the ladder can sit
	 * above their own ceiling, which would otherwise read as "195 of 64".
	 */
	public int pointsIntoTier() {
		if (tierCeiling <= tierFloor) {
			return 0;
		}
		return Math.clamp(rating - tierFloor, 0, tierCeiling - tierFloor);
	}

	/** How wide the current tier is, in rating points. */
	public int tierSpan() {
		return Math.max(0, tierCeiling - tierFloor);
	}

	/** True when the tier's bounds are known, so progress can be shown. */
	public boolean hasTierBounds() {
		return tierCeiling > tierFloor;
	}

	public boolean hasNextTier() {
		return nextTier != null && !nextTier.isEmpty();
	}
}
