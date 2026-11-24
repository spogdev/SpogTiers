package dev.spog.tiers.util;

import dev.spog.tiers.SpogTiersClient;
import dev.spog.tiers.config.SpogTiersConfig;
import dev.spog.tiers.config.TagLayout;
import dev.spog.tiers.data.Gamemode;
import dev.spog.tiers.data.PlayerTiers;
import dev.spog.tiers.data.Regions;
import dev.spog.tiers.data.Tier;
import dev.spog.tiers.data.TierList;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.world.GameMode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

	private TabSorter() {
	}

	/**
	 * One player's sort keys, worked out once before the sort begins.
	 *
	 * <p>Every key is a plain field rather than a lookup. A player's tier and
	 * region are read from a cache a background thread fills in, so asking for
	 * them inside a comparison lets the same pair compare differently from one
	 * moment to the next. That breaks the transitivity a sort requires, and
	 * TimSort notices and throws "Comparison method violates its general
	 * contract".
	 */
	private record Key(int primary, int secondary, int regionPrimary,
			int regionSecondary, String regionNamePrimary, String regionNameSecondary,
			String name, int listOrder, int spectator) {
	}

	/**
	 * The tab list in the configured order.
	 *
	 * <p>Returns the list it was given, untouched, whenever there is nothing to
	 * do: no config yet, or both sorts left on Server. A copy is made before
	 * sorting because the list vanilla hands over may be immutable.
	 */
	public static List<PlayerListEntry> sort(List<PlayerListEntry> players) {
		SpogTiersConfig config = SpogTiersClient.config();
		if (config == null || players == null || players.size() < 2) {
			return players;
		}
		// Over the limit the list is handed back as vanilla built it. Counted
		// on the tab list itself, which vanilla caps at 80 entries however
		// many players are connected -- so the limit is a tab-list size, not a
		// server population. Checked before anything is measured, so a list
		// over the limit costs nothing rather than being sorted and discarded.
		if (config.tabSortLimited && players.size() > config.tabSortLimit) {
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

		// Measured first, then sorted: see Key. The comparison below reads
		// nothing but these fields.
		Map<UUID, Key> keys = new HashMap<>();
		for (PlayerListEntry player : players) {
			UUID id = idOf(player);
			if (id != null && !keys.containsKey(id)) {
				keys.put(id, keyFor(player, id, primary, secondary));
			}
		}
		// For an entry with no profile id, which cannot be measured. Every
		// such entry shares it, so they tie with each other and sort last.
		Key fallback = new Key(UNRANKED, UNRANKED, REGION_ORDER.size() + 1,
				REGION_ORDER.size() + 1, "", "", "", 0, 0);

		Comparator<Key> order = Comparator.comparingInt(Key::primary)
				.thenComparingInt(Key::regionPrimary)
				.thenComparing(Key::regionNamePrimary)
				.thenComparingInt(Key::secondary)
				.thenComparingInt(Key::regionSecondary)
				.thenComparing(Key::regionNameSecondary)
				.thenComparingInt(Key::listOrder)
				// Vanilla's last resort, kept as ours: two players who tie on
				// everything above must still come out in a stable order, or
				// the list reshuffles between frames.
				.thenComparing(Key::name, String.CASE_INSENSITIVE_ORDER);

		if (!config.tabSortSpectators) {
			// Spectators pinned after everyone else, as vanilla has them, and
			// sorted among themselves rather than left in arrival order.
			order = Comparator.<Key>comparingInt(Key::spectator).thenComparing(order);
		}
		Comparator<Key> keyOrder = order;

		List<PlayerListEntry> sorted = new ArrayList<>(players);
		sorted.sort((left, right) -> keyOrder.compare(
				keys.getOrDefault(idOf(left), fallback),
				keys.getOrDefault(idOf(right), fallback)));
		return sorted;
	}

	/**
	 * Every key this player could be sorted on, for the two chosen sorts.
	 *
	 * <p>A field per sort slot rather than per sort kind, because the primary
	 * and the secondary may be the same kind. A field the chosen sorts do not
	 * use is filled with a neutral value, so the comparison passes through it.
	 */
	private static Key keyFor(PlayerListEntry player, UUID id,
			SpogTiersConfig.TabSort primary, SpogTiersConfig.TabSort secondary) {
		String name = nameOf(player);
		boolean wantsRegion = primary == SpogTiersConfig.TabSort.REGION
				|| secondary == SpogTiersConfig.TabSort.REGION;
		// Resolved once even though two slots may want it.
		String region = wantsRegion ? regionOf(id) : "";
		int regionRank = wantsRegion ? regionRank(region) : 0;
		boolean wantsServer = primary == SpogTiersConfig.TabSort.SERVER
				|| secondary == SpogTiersConfig.TabSort.SERVER;

		return new Key(
				rankOf(primary, id),
				rankOf(secondary, id),
				primary == SpogTiersConfig.TabSort.REGION ? regionRank : 0,
				secondary == SpogTiersConfig.TabSort.REGION ? regionRank : 0,
				primary == SpogTiersConfig.TabSort.REGION ? region : "",
				secondary == SpogTiersConfig.TabSort.REGION ? region : "",
				name,
				// Negated exactly as vanilla negates it, so a higher order
				// sorts earlier.
				wantsServer ? -player.getListOrder() : 0,
				spectatorRank(player));
	}

	/**
	 * The tier rank this sort kind orders by, or zero for a kind that orders
	 * on one of the Key's other fields.
	 */
	private static int rankOf(SpogTiersConfig.TabSort sort, UUID id) {
		return switch (sort) {
			case TIER_BEST -> bestRank(id);
			case TIER_FIRST -> shownRank(id);
			// Region orders on regionPrimary/regionNamePrimary, Alphabetical on
			// name and Server on listOrder, so none of them has a rank here.
			case REGION, ALPHABETICAL, SERVER -> 0;
		};
	}

	/** Spectators last, as vanilla orders them. */
	private static int spectatorRank(PlayerListEntry player) {
		return player.getGameMode() == GameMode.SPECTATOR ? 1 : 0;
	}

	private static String nameOf(PlayerListEntry player) {
		return player.getProfile().name();
	}

	private static UUID idOf(PlayerListEntry player) {
		return player.getProfile().id();
	}

	/** The player's best rank across every enabled list. */
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
		return best;
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
	 * <p>Mirrors the element kinds the renderer honours: a Best across every
	 * list, or one list -- at a fixed gamemode or at that list's own best.
	 */
	private static int rankOf(UUID uuid, TagLayout.Element element) {
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
	private static int regionRank(String code) {
		if (code.isEmpty()) {
			return REGION_ORDER.size() + 1;
		}
		int place = REGION_ORDER.indexOf(code);
		return place >= 0 ? place : REGION_ORDER.size();
	}
}
