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
	public SortOrder sortOrder = SortOrder.DEFAULT;

	/**
	 * Show gamemodes a player is still placing into.
	 *
	 * <p>Only PVPHQ reports these. They carry no tier yet, so hiding them
	 * leaves a card showing just the ranks actually held.
	 */
	public boolean showPlacements = true;

	/** Show the region code before the name. */
	public boolean showRegionOnNametag = false;

	/** Where tier tags appear. */
	public boolean showInChat = false;

	/** What the left-hand tag shows, or null for none. */
	public TagSlot leftTag = new TagSlot(true, TierList.PVPTIERS, null);

	/** What the right-hand tag shows, or null for none. */
	public TagSlot rightTag = new TagSlot(false, TierList.PVPTIERS, null);

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
		DEFAULT("Default"),
		DATE_OBTAINED("Date obtained"),
		RANKING("Ranking"),
		RANKING_PEAK("Ranking (Peak inclusive)");

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
		if (displayList == null) {
			displayList = TierList.PVPTIERS;
		}
		if (displayMode == null) {
			displayMode = Gamemode.VANILLA;
		}
		if (sortOrder == null) {
			sortOrder = SortOrder.DEFAULT;
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
