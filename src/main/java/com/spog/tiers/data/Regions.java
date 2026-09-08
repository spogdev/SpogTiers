package com.spog.tiers.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Works out which region a player actually belongs to.
 *
 * <p>No single list is authoritative. MCTiers, SubTiers and MCPvP each publish
 * a region the player picked, and they disagree often enough to matter -- one
 * sampled player reads EU on MCTiers and AS on SubTiers. So rather than trust
 * whichever list happens to be checked first, every list that states a region
 * gets a vote and the most common answer wins.
 *
 * <p>PVPHQ is handled separately because it does not publish a region code at
 * all. It gives a home country and the server location the player queues
 * closest to, both of which fold down to a continent -- but a location is where
 * someone gets a good ping, not where they live, so PVPHQ only breaks ties or
 * answers when nobody else did.
 */
public final class Regions {
	/** What a list stating a region is worth. */
	private static final int ORDINARY_VOTE = 2;
	/**
	 * What PVPHQ's region is worth.
	 *
	 * <p>Three against two, so it outweighs any single list and settles a
	 * two-way split on its own, but two lists agreeing against it still win.
	 * Its answer is a home country rather than a picked region, which makes it
	 * the better single signal without making it the only one.
	 */
	private static final int PVPHQ_VOTE = 3;

	private Regions() {
	}

	/**
	 * The player's region, or "" when nothing usable was reported.
	 *
	 * <p>Ties go to the region PVPHQ points at, then to the first list in
	 * declaration order, so the answer is stable between calls rather than
	 * depending on map iteration.
	 */
	public static String resolve(Map<TierList, PlayerTiers> lists) {
		if (lists == null || lists.isEmpty()) {
			return "";
		}

		// Ordered so that an unbroken tie falls to the earliest list.
		Map<String, Integer> votes = new LinkedHashMap<>();
		for (TierList list : TierList.values()) {
			if (list.isPvpHq()) {
				continue;
			}
			PlayerTiers tiers = lists.get(list);
			if (tiers == null) {
				continue;
			}
			String code = normalise(tiers.region());
			if (!code.isEmpty()) {
				votes.merge(code, ORDINARY_VOTE, Integer::sum);
			}
		}

		String pvpHq = pvpHqRegion(lists.get(TierList.PVPHQ));

		if (votes.isEmpty()) {
			// Nobody stated a region, so PVPHQ's guess is all there is.
			return pvpHq;
		}

		// PVPHQ votes with the rest rather than only breaking ties, and its
		// vote counts for more: it reports a home country, where the others
		// report whatever region the player picked for themselves.
		if (!pvpHq.isEmpty()) {
			votes.merge(pvpHq, PVPHQ_VOTE, Integer::sum);
		}

		int best = 0;
		for (int count : votes.values()) {
			best = Math.max(best, count);
		}

		List<String> leaders = new ArrayList<>();
		for (Map.Entry<String, Integer> vote : votes.entrySet()) {
			if (vote.getValue() == best) {
				leaders.add(vote.getKey());
			}
		}

		if (leaders.size() == 1) {
			return leaders.get(0);
		}
		// Still level: PVPHQ decides, if it is one of the leaders.
		return leaders.contains(pvpHq) ? pvpHq : leaders.get(0);
	}

	/** PVPHQ's country if it is shown, else the server location it reported. */
	private static String pvpHqRegion(PlayerTiers pvpHq) {
		return pvpHq == null ? "" : continentOf(pvpHq.region());
	}

	/**
	 * A region code in upper case, or "" if the value is not one.
	 *
	 * <p>MCTiers returns a literal {@code "??"} for players who never picked
	 * one, which must not become a vote.
	 */
	public static String normalise(String raw) {
		if (raw == null || raw.length() < 2 || raw.length() > 4) {
			return "";
		}
		for (int i = 0; i < raw.length(); i++) {
			if (!Character.isLetter(raw.charAt(i))) {
				return "";
			}
		}
		String code = raw.toUpperCase(Locale.ROOT);
		// Oceania under one spelling. The lists send OCE and we fold their
		// countries to it too, so without this the same region votes as two
		// and can lose to a region neither voter meant.
		return code.equals("OCE") ? "OC" : code;
	}

	/**
	 * Folds a PVPHQ country or server location down to a continent code.
	 *
	 * <p>Both share this method because the two fields carry the same kind of
	 * value -- a place name -- and PVPHQ sends whichever it has.
	 */
	public static String continentOf(String place) {
		if (place == null || place.isEmpty()) {
			return "";
		}
		return switch (place.toUpperCase(Locale.ROOT)) {
			// Server locations.
			case "MONTREAL", "TORONTO", "LOS_ANGELES", "PORTLAND", "CHICAGO",
					"ASHBURN", "MIAMI", "DALLAS", "NEW_YORK", "SEATTLE",
					"DENVER", "ATLANTA", "PHOENIX", "VANCOUVER" -> "NA";
			case "LONDON", "FRANKFURT", "AMSTERDAM", "PARIS", "WARSAW",
					"MADRID", "MILAN", "STOCKHOLM", "HELSINKI", "DUBLIN" -> "EU";
			case "SINGAPORE", "TOKYO", "SEOUL", "MUMBAI", "HONG_KONG",
					"OSAKA", "JAKARTA" -> "AS";
			case "SYDNEY", "MELBOURNE", "AUCKLAND" -> "OC";
			case "SAO_PAULO", "SANTIAGO", "BUENOS_AIRES", "LIMA", "BOGOTA" -> "SA";
			case "JOHANNESBURG", "CAPE_TOWN", "LAGOS" -> "AF";

			// Countries, which PVPHQ sends when the player shows theirs.
			case "UNITED_STATES", "CANADA", "MEXICO" -> "NA";
			case "UNITED_KINGDOM", "IRELAND", "FRANCE", "GERMANY", "SPAIN",
					"PORTUGAL", "ITALY", "THE_NETHERLANDS", "NETHERLANDS",
					"BELGIUM", "SWITZERLAND", "AUSTRIA", "POLAND", "CZECHIA",
					"SLOVAKIA", "HUNGARY", "ROMANIA", "BULGARIA", "GREECE",
					"CROATIA", "SERBIA", "SLOVENIA", "DENMARK", "SWEDEN",
					"NORWAY", "FINLAND", "ICELAND", "ESTONIA", "LATVIA",
					"LITHUANIA", "UKRAINE", "BELARUS", "RUSSIA", "MOLDOVA",
					"ALBANIA", "BOSNIA_AND_HERZEGOVINA", "NORTH_MACEDONIA",
					"MONTENEGRO", "LUXEMBOURG", "MALTA", "CYPRUS" -> "EU";
			case "CHINA", "JAPAN", "SOUTH_KOREA", "INDIA", "PAKISTAN",
					"BANGLADESH", "INDONESIA", "MALAYSIA", "SINGAPORE_COUNTRY",
					"THAILAND", "VIETNAM", "PHILIPPINES", "TAIWAN",
					"KAZAKHSTAN", "GEORGIA", "ARMENIA", "AZERBAIJAN",
					"NEPAL", "SRI_LANKA" -> "AS";
			case "TÜRKIYE", "TURKIYE", "TURKEY", "SAUDI_ARABIA",
					"UNITED_ARAB_EMIRATES", "QATAR", "KUWAIT", "BAHRAIN",
					"OMAN", "ISRAEL", "JORDAN", "LEBANON", "IRAQ", "IRAN",
					"SYRIA", "YEMEN" -> "ME";
			case "AUSTRALIA", "NEW_ZEALAND" -> "OC";
			case "BRAZIL", "ARGENTINA", "CHILE", "PERU", "COLOMBIA",
					"URUGUAY", "PARAGUAY", "BOLIVIA", "ECUADOR",
					"VENEZUELA" -> "SA";
			case "SOUTH_AFRICA", "NIGERIA", "EGYPT", "MOROCCO", "ALGERIA",
					"TUNISIA", "KENYA", "GHANA", "ETHIOPIA" -> "AF";
			default -> "";
		};
	}
}
