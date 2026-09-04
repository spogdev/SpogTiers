package com.spog.tiers.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.spog.tiers.SpogTiers;
import com.spog.tiers.data.Gamemode;
import com.spog.tiers.data.TierList;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/** User-editable settings, persisted to {@code config/spogtiers.json}. */
public class SpogTiersConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Master switch for all tag rendering. */
	public boolean enabled = true;

	/** Show tags above player heads in the world. */
	public boolean showNametags = true;

	/** Show tags in the tab player list. */
	public boolean showTabList = true;

	/** Which tier list the badge and panel headline come from. */
	public TierList displayList = TierList.PVPTIERS;

	/** Which gamemode's tier the badge shows. */
	public Gamemode displayMode = Gamemode.VANILLA;

	/** Show the best tier across all gamemodes instead of {@link #displayMode}. */
	public boolean showBestTier = true;

	/** Per-list toggles; a disabled list is never queried. */
	public Map<TierList, Boolean> enabledLists = defaultLists();

	/** How long a cached lookup stays fresh, in seconds. */
	public int cacheTtlSeconds = 900;

	/** Max lookups dispatched per second, to stay friendly to the APIs. */
	public int requestsPerSecond = 5;

	/** How the rows inside each tier list card are ordered. */
	public SortOrder sortOrder = SortOrder.RANKING;

	/**
	 * Show retirement, rather than presenting a retired rank as an active one.
	 *
	 * <p>Off, an RHT1 reads and colours exactly as an HT1 everywhere: the R
	 * goes, and the washed-out retired colour with it. Two exceptions. Our own
	 * Door SMP tierlist still shows its R, because retirement there is the
	 * point of the list rather than an annotation on someone else's. And the
	 * profile tooltip still says so, because a tooltip is where someone asks
	 * for the detail the label left out.
	 */
	public boolean showRetired = true;

	/**
	 * Draw the ember aura around graded players and on their profile.
	 *
	 * <p>Separate from {@link #extraTierlists}, which turns our tierlist off
	 * altogether: this leaves the door tag showing and only stops the
	 * particles, for someone who wants the tier without the decoration around
	 * every graded player they walk past.
	 */
	public boolean showParticles = true;

	/**
	 * Show gamemodes a player is still placing into.
	 *
	 * <p>Only PVPHQ reports these. They carry no tier yet, so hiding them
	 * leaves a card showing just the ranks actually held.
	 */
	public boolean showPlacements = true;

	/**
	 * Our own Door SMP tierlist: the badge beside the region tag and the embers
	 * around the model.
	 *
	 * <p>One switch for all of it. It is a first-party addition on top of the
	 * six read-only lists, so someone who only wants those can turn the whole
	 * thing off without hunting through several options.
	 */
	public boolean extraTierlists = true;

	/**
	 * Per-list tag toggles, separate from {@link #enabledLists}.
	 *
	 * <p>A list can be worth reading on the profile screen without being worth
	 * quoting on a nametag -- CatPVP is the obvious case, since its ranks are
	 * not directly comparable with the HT/LT lists, so a Best tag can end up
	 * quoting it where another list is the fairer read.
	 */
	public Map<TierList, Boolean> taggedLists = defaultLists();

	/**
	 * Draw the {@code |} between a nametag's parts.
	 *
	 * <p>Off, the parts are spaced instead. The separators read as structure
	 * on a busy server where several mods write into the same plate, and as
	 * clutter on a quiet one, so it is left to taste.
	 */
	public boolean showSeparators = true;

	/** Show the region code before the name. */
	public boolean showRegionOnNametag = false;

	/** Where tier tags appear. */
	public boolean showInChat = false;

	/** What the left-hand tag shows, or null for none. */
	public TagSlot leftTag = new TagSlot(true, TierList.PVPTIERS, null);

	/** What the right-hand tag shows, or null for none. */
	public TagSlot rightTag = new TagSlot(false, TierList.PVPTIERS, null);

	/**
	 * Whether the two nametag slots may show the same tier.
	 *
	 * <p>With both set to Best they often resolve to the same ranking, which
	 * says nothing twice and wastes the second slot. When this is on the right
	 * slot falls back to the best tier it can find that is not already on the
	 * left, and shows nothing if there is no such thing.
	 */
	public boolean preventDuplicateTiers = true;

	/** What the tag above the name shows, or null for none. */
	public TagSlot aboveTag = new TagSlot(false, TierList.PVPTIERS, null);

	/**
	 * Whether to tag nametags a server draws with a text display.
	 *
	 * <p>Servers use those to colour or style a name, which vanilla does not
	 * allow on a real nameplate. The display is a separate entity, so without
	 * this the tag goes missing on exactly the servers that care most about
	 * how names look.
	 */
	public boolean tagDisplays = true;

	/**
	 * Show the gamemode icon alongside the tier in tags.
	 *
	 * <p>Always on: the icon is what makes a bare "HT1" legible at a glance,
	 * so it is no longer exposed as a setting.
	 */
	public final boolean showTagIcons = true;

	/**
	 * One side of the nametag. A null {@code gamemode} means "their best tier
	 * on that list", which is what most people want by default; a null
	 * {@code list} means "across every list", the Best option.
	 */
	public static class TagSlot {
		public boolean enabled;
		public TierList list;
		public Gamemode gamemode;

		public TagSlot() {
		}

		public TagSlot(boolean enabled, TierList list, Gamemode gamemode) {
			this.enabled = enabled;
			this.list = list;
			this.gamemode = gamemode;
		}
	}

	/**
	 * Row ordering inside a tier list card.
	 *
	 * <p>{@link #DEFAULT} is the gamemode order the mod declares, which keeps
	 * the same mode in the same place across every card and makes the grid easy
	 * to scan. The other two sort by the data instead.
	 */
	public enum SortOrder {
		/** Whatever order the provider sent, which is its own default. */
		DEFAULT("Received"),
		DATE_OBTAINED("Date obtained"),
		RANKING("Ranking"),
		RANKING_PEAK("Ranking (Include peak)");

		private final String title;

		SortOrder(String title) {
			this.title = title;
		}

		public String title() {
			return title;
		}
	}

	/** Panel background opacity, 0-255. */
	public int panelOpacity = 190;

	/** Slowly spin the skin model in the profile panel. */
	public boolean rotateSkin = true;

	private static Map<TierList, Boolean> defaultLists() {
		Map<TierList, Boolean> map = new EnumMap<>(TierList.class);
		for (TierList list : TierList.values()) {
			map.put(list, true);
		}
		return map;
	}

	public boolean isEnabled(TierList list) {
		return enabledLists == null || enabledLists.getOrDefault(list, true);
	}

	/**
	 * Whether this list may appear on a nametag.
	 *
	 * <p>A hidden list is never queried, so it cannot be tagged either however
	 * this is set.
	 */
	public boolean isTagged(TierList list) {
		return isEnabled(list)
				&& (taggedLists == null || taggedLists.getOrDefault(list, true));
	}

	public void setTagged(TierList list, boolean value) {
		if (taggedLists == null) {
			taggedLists = defaultLists();
		}
		taggedLists.put(list, value);
	}

	public void setEnabled(TierList list, boolean value) {
		if (enabledLists == null) {
			enabledLists = defaultLists();
		}
		enabledLists.put(list, value);
	}

	public static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve(SpogTiers.MOD_ID + ".json");
	}

	public static SpogTiersConfig load() {
		Path path = path();
		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path)) {
				SpogTiersConfig loaded = GSON.fromJson(reader, SpogTiersConfig.class);
				if (loaded != null) {
					loaded.normalise();
					return loaded;
				}
			} catch (Exception e) {
				SpogTiers.LOGGER.warn("Could not read config, using defaults", e);
			}
		}
		SpogTiersConfig fresh = new SpogTiersConfig();
		fresh.save();
		return fresh;
	}

	/** Fills in anything an older config file predates. */
	private void normalise() {
		if (enabledLists == null) {
			enabledLists = defaultLists();
		} else {
			for (TierList list : TierList.values()) {
				enabledLists.putIfAbsent(list, true);
			}
		}
		// A config written before tag toggles existed has no map at all, and
		// one written before a list was added is missing that entry.
		if (taggedLists == null) {
			taggedLists = defaultLists();
		} else {
			for (TierList list : TierList.values()) {
				taggedLists.putIfAbsent(list, true);
			}
		}
		if (displayList == null) {
			displayList = TierList.PVPTIERS;
		}
		if (displayMode == null) {
			displayMode = Gamemode.VANILLA;
		}
		if (sortOrder == null) {
			sortOrder = SortOrder.RANKING;
		}
		if (leftTag == null) {
			leftTag = new TagSlot(true, TierList.PVPTIERS, null);
		}
		if (rightTag == null) {
			rightTag = new TagSlot(false, TierList.PVPTIERS, null);
		}
		// A null list is meaningful here (Best, across every list), so unlike
		// the fields above it is deliberately left alone.
		panelOpacity = Math.clamp(panelOpacity, 0, 255);
		requestsPerSecond = Math.max(1, requestsPerSecond);
	}

	public void save() {
		Path path = path();
		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException e) {
			SpogTiers.LOGGER.error("Could not write config", e);
		}
	}
}
