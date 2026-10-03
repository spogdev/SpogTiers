package dev.spog.tiers.util;

import dev.spog.tiers.SpogTiersClient;
import dev.spog.tiers.config.SpogTiersConfig;
import dev.spog.tiers.config.TagLayout;
import dev.spog.tiers.data.Gamemode;
import dev.spog.tiers.data.PlayerGrade;
import dev.spog.tiers.data.PlayerTiers;
import dev.spog.tiers.data.Regions;
import dev.spog.tiers.data.Tier;
import dev.spog.tiers.data.TierList;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Orders the tab list by tier, region, name or the server's own ordering.
 *
 * <p>Sorting happens over the list vanilla has already built, so anything this
 * does not have an opinion about keeps the order the game gave it.
 */
public final class TabSorter {

	/**
	 * Where a player with no tier sorts: after every ranked one.
	 *
	 * <p>Larger than any {@link Tier#ladderOrdinal()}, which tops out in the
	 * low hundreds, and well clear of it so a new rank added to either ladder
	 * cannot overtake it.
	 */
	private static final int UNRANKED = 10_000;

	/**
	 * Region order, best first.
	 *
	 * <p>The first four were asked for; the rest are every other region the
	 * mod recognises, taken from the profile screen's own region table --
	 * South America, Africa and the Middle East. Anything unrecognised sorts
	 * after all of them, grouped rather than scattered.
	 */
	private static final List<String> REGION_ORDER =
			List.of("NA", "EU", "AS", "OC", "SA", "AF", "ME");

	/**
	 * Region spellings that mean one of the above.
	 *
	 * <p>{@link Regions#normalise} folds OCE to OC but leaves AU alone, and
	 * NovaTiers sends AU -- so without this an Oceanian player sorts into the
	 * unrecognised group at the end rather than with Oceania.
	 */
	private static final Map<String, String> REGION_ALIASES = Map.of("AU", "OC", "OCE", "OC");

	/**
	 * Door SMP's ladder, best first.
	 *
	 * <p>Its grades are letters with no tier number, so they cannot go through
	 * {@link Tier#ladderOrdinal()} and need their own order. Kept in step with
	 * the backend's own Grade enum.
	 */
	private static final List<String> DOOR_ORDER =
			List.of("S", "A+", "A", "B+", "B", "C", "D", "F");

	private TabSorter() {
	}

	/**
	 * The tab list in the configured order.
	 *
	 * <p>Returns the list it was given, untouched, whenever there is nothing to
	 * do: no config yet, or both sorts left on Server. A copy is made before
	 * sorting because the list vanilla hands over may be immutable.
	 */
	public static List<PlayerInfo> sort(List<PlayerInfo> players) {
		SpogTiersConfig config = SpogTiersClient.config();
		if (config == null || players == null || players.size() < 2) {
			return players;
		}
		SpogTiersConfig.TabSort primary = config.tabPrimarySort;
		SpogTiersConfig.TabSort secondary = config.tabSecondarySort;
		// Server for both means vanilla's own order, which the list is already
		// in. Sorting it again by the same keys would only risk disagreeing.
		if (primary == SpogTiersConfig.TabSort.SERVER
				&& secondary == SpogTiersConfig.TabSort.SERVER) {
			return players;
		}

		Comparator<PlayerInfo> order = comparator(primary)
				.thenComparing(comparator(secondary))
				// Vanilla's last resort, kept as ours: two players who tie on
				// everything above must still come out in a stable order, or
				// the list reshuffles between frames.
				.thenComparing(TabSorter::nameOf, String.CASE_INSENSITIVE_ORDER);

		if (!config.tabSortSpectators) {
			// Spectators pinned after everyone else, as vanilla has them, and
			// sorted among themselves rather than left in arrival order.
			order = Comparator.comparingInt(TabSorter::spectatorRank).thenComparing(order);
		}

		List<PlayerInfo> sorted = new ArrayList<>(players);
		sorted.sort(order);
		return sorted;
	}

	/** One sort key. Ties are left to whatever comparator follows. */
	private static Comparator<PlayerInfo> comparator(SpogTiersConfig.TabSort sort) {
		return switch (sort) {
			case TIER_BEST -> Comparator.comparingInt(player -> bestRank(idOf(player)));
			case TIER_FIRST -> Comparator.comparingInt(player -> shownRank(idOf(player)));
			// Typed explicitly: the chained thenComparing leaves the lambda
			// parameter uninferable inside a switch arm.
			case REGION -> Comparator
					.<PlayerInfo>comparingInt(player -> regionRank(idOf(player)))
					.thenComparing(player -> regionOf(idOf(player)));
			case ALPHABETICAL -> Comparator.comparing(TabSorter::nameOf,
					String.CASE_INSENSITIVE_ORDER);
			// The server's own ordering, which is what vanilla sorts by first.
			// Negated exactly as vanilla negates it, so a higher order sorts
			// earlier.
			case SERVER -> Comparator.comparingInt(player -> -player.getTabListOrder());
		};
	}

	/** Spectators last, as vanilla orders them. */
	private static int spectatorRank(PlayerInfo player) {
		return player.getGameMode() == GameType.SPECTATOR ? 1 : 0;
	}

	private static String nameOf(PlayerInfo player) {
		return player.getProfile().name();
	}

	private static UUID idOf(PlayerInfo player) {
		return player.getProfile().id();
	}

	/**
	 * The player's best rank across every enabled list, Door SMP included.
	 *
	 * <p>Door SMP is mapped onto the same scale as the numbered lists so one
	 * comparison covers both: its grades are a ladder of eight, the tier lists
	 * a ladder of five with three positions each, and a player on both sorts by
	 * whichever placement is stronger.
	 */
	private static int bestRank(UUID uuid) {
		SpogTiersConfig config = SpogTiersClient.config();
		if (uuid == null || config == null) {
			return UNRANKED;
		}
		int best = UNRANKED;

		for (Map.Entry<TierList, PlayerTiers> entry
				: SpogTiersClient.cache().allLists(uuid).entrySet()) {
			PlayerTiers tiers = entry.getValue();
			if (tiers == null || !config.isEnabled(entry.getKey())) {
				continue;
			}
			Tier tier = tiers.best();
			if (tier != null && tier.isRanked()) {
				best = Math.min(best, tier.ladderOrdinal());
			}
		}
		return Math.min(best, doorRank(uuid));
	}

	/**
	 * Door SMP's grade on the tier lists' scale.
	 *
	 * <p>Eight grades spread over the five tiers the other lists use, so S
	 * lands with tier 1 and F below tier 5. Spread rather than packed at the
	 * top: a C on our list should not outrank an HT2 on MCTiers.
	 */
	private static int doorRank(UUID uuid) {
		SpogTiersConfig config = SpogTiersClient.config();
		if (config == null || !config.extraTierlists) {
			return UNRANKED;
		}
		PlayerGrade grade = SpogTiersClient.service().grade(uuid);
		if (grade == null || !grade.isGraded()) {
			return UNRANKED;
		}
		int place = DOOR_ORDER.indexOf(grade.grade().toUpperCase(Locale.ROOT));
		if (place < 0) {
			return UNRANKED;
		}
		// The numbered ladder runs tier * 20 + position * 2 (see
		// Tier.ladderOrdinal, doubled there for the named-rank tiebreak), so
		// tier 1 starts at 20 and tier 5 at 100. Eight grades over that span
		// is a step of 10, placing S with tier 1 and F just past LT5.
		//
		// The +1 is what Tier.ladderOrdinal does for a named rank, and for the
		// same reason: landing exactly on an HT slot would tie, and a tie here
		// would be broken by the secondary sort rather than by the grade. An S
		// now sits just below HT1 -- the one placement it cannot beat.
		return 20 + place * 10 + 1;
	}

	/**
	 * The rank of the first tier the nametag shows for this player.
	 *
	 * <p>Middle row first, then top, then bottom, taking the first tier
	 * element in each that resolves to a real placement -- so the list sorts by
	 * what is actually drawn beside the name rather than by data that may not
	 * be on screen.
	 */
	private static int shownRank(UUID uuid) {
		SpogTiersConfig config = SpogTiersClient.config();
		if (uuid == null || config == null || config.tagLayout == null) {
			return UNRANKED;
		}
		for (TagLayout.Row row : new TagLayout.Row[] {
				TagLayout.Row.MIDDLE, TagLayout.Row.TOP, TagLayout.Row.BOTTOM}) {
			for (TagLayout.Element element : config.tagLayout.row(row)) {
				if (element.kind != TagLayout.Kind.TIER) {
					continue;
				}
				int rank = rankOf(uuid, element);
				if (rank != UNRANKED) {
					return rank;
				}
			}
		}
		return UNRANKED;
	}

	/**
	 * What one tier element of the layout resolves to for this player.
	 *
	 * <p>Mirrors the element kinds the renderer honours: our own list, a Best
	 * across every list, or one list -- at a fixed gamemode or at that list's
	 * own best.
	 */
	private static int rankOf(UUID uuid, TagLayout.Element element) {
		if (element.doorSmp) {
			return doorRank(uuid);
		}
		if (element.list() == null) {
			return bestRank(uuid);
		}
		SpogTiersConfig config = SpogTiersClient.config();
		if (!config.isEnabled(element.list())) {
			return UNRANKED;
		}
		PlayerTiers tiers = SpogTiersClient.cache().get(uuid, element.list());
		if (tiers == null) {
			return UNRANKED;
		}
		Gamemode mode = element.gamemode;
		Tier tier = mode == null ? tiers.best() : tiers.get(mode);
		return tier != null && tier.isRanked() ? tier.ladderOrdinal() : UNRANKED;
	}

	/** The player's region, or empty when no list gives one. */
	private static String regionOf(UUID uuid) {
		if (uuid == null) {
			return "";
		}
		String code = Regions.resolve(SpogTiersClient.cache().allLists(uuid));
		if (code == null) {
			return "";
		}
		String normalised = Regions.normalise(code);
		return REGION_ALIASES.getOrDefault(normalised, normalised);
	}

	/**
	 * Where a region sorts: the listed ones in their given order, then any
	 * other region, then players whose region is unknown.
	 */
	private static int regionRank(UUID uuid) {
		String code = regionOf(uuid);
		if (code.isEmpty()) {
			return REGION_ORDER.size() + 1;
		}
		int place = REGION_ORDER.indexOf(code);
		return place >= 0 ? place : REGION_ORDER.size();
	}
}
