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
import java.nio.charset.StandardCharsets;
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
	 * <p>Only the particles: a door tier stays on the tag and on the profile,
	 * for someone who wants the tier without the decoration around every
	 * graded player they walk past.
	 */
	public boolean showParticles = true;

	/**
	 * Show a player's peak tier beside their current one in their profile.
	 *
	 * <p>Drawn struck through, as the tier they used to hold. Off leaves only
	 * the tier they hold now, for someone who reads the list as it stands
	 * rather than as it was.
	 */
	public boolean showPeakTiers = true;

	/**
	 * Show the linked Discord account under a player's name in their profile.
	 *
	 * <p>Off by default, and off means the line is not drawn and the account
	 * is never asked for: the lookup leaves for our own service, so a profile
	 * only reaches out for someone's Discord handle once you have said it
	 * should. Opting in is the right way round for that.
	 */
	public boolean showDiscord = false;

	/**
	 * Let Nametag Tweaks' adjustments reach our extra tag rows.
	 *
	 * <p>That mod scales, raises and recolours the nameplate by wrapping
	 * vanilla's drawing, which our own rows do not go through -- so without
	 * this the middle line moves and the other two stay put. On by default,
	 * because a tag whose lines disagree is never what anyone wanted; off for
	 * someone who would rather the tier rows kept vanilla's own placement.
	 */
	public boolean matchNametagTweaks = true;

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

	// NB: no longer has a switch on the config screen. Door SMP is asked for
	// by name in the tag editor now, and the profile aura has its own
	// Particles option, so a second master switch only gave two ways to turn
	// the same things off. The field stays so an existing config that turned
	// it off is still honoured, and forced back on below.

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

	/**
	 * Where the region code sits, or {@link RegionSlot#OFF} to hide it.
	 *
	 * <p>Null in a config written before this was a dropdown; see
	 * {@link #showRegionOnNametag}, which it replaces.
	 */
	public RegionSlot regionSlot = null;

	/**
	 * The old on/off switch, kept only so an existing config can be read.
	 *
	 * <p>It has to keep its own field rather than being reused as the enum:
	 * Gson throws on a boolean where it wants a string, and the load catches
	 * that and falls back to a fresh config -- so reusing the name would have
	 * quietly reset every other setting the user had. {@link #normalise}
	 * carries the value across and nothing reads it afterwards.
	 *
	 * @deprecated superseded by {@link #regionSlot}
	 */
	@Deprecated
	public Boolean showRegionOnNametag = null;

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
	 * The nametag as arranged in the editor.
	 *
	 * <p>Supersedes {@link #leftTag}, {@link #rightTag} and {@link #aboveTag},
	 * which could only hold a tier each and only in that order. Null in a
	 * config written before the editor existed; {@link #normalise} builds one
	 * from the old slots so an upgrade keeps the tag someone had.
	 */
	public TagLayout tagLayout = null;

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
	/**
	 * Where the region code is drawn.
	 *
	 * <p>Two rows and two sides. The top row is the line above the name, drawn
	 * beside the above tag; the bottom row is the nameplate itself, drawn
	 * outside the tier tags on that side.
	 */
	public enum RegionSlot {
		OFF("Disabled"),
		TOP_LEFT("Top Left"),
		TOP_RIGHT("Top Right"),
		BOTTOM_LEFT("Bottom Left"),
		BOTTOM_RIGHT("Bottom Right");

		private final String title;

		RegionSlot(String title) {
			this.title = title;
		}

		public String title() {
			return title;
		}

		/** Whether this slot is drawn at all. */
		public boolean shown() {
			return this != OFF;
		}

		/** Whether it belongs on the line above the name rather than the name itself. */
		public boolean top() {
			return this == TOP_LEFT || this == TOP_RIGHT;
		}

		/** Whether it goes before what is on that row rather than after it. */
		public boolean before() {
			return this == TOP_LEFT || this == BOTTOM_LEFT;
		}
	}

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
			try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				SpogTiersConfig loaded = GSON.fromJson(reader, SpogTiersConfig.class);
				if (loaded != null) {
					loaded.normalise();
					return loaded;
				}
			} catch (Exception e) {
				SpogTiers.LOGGER.warn("Could not read config, using defaults", e);
			}
		}
		// Normalised before saving, like a loaded one: the layout starts null
		// so that an upgrade can tell "never had one" from "has an empty one",
		// and without this a fresh config was written with no layout at all --
		// which Gson omits, so the editor's own saves had nothing to merge
		// into and every tier element was lost on restart.
		SpogTiersConfig fresh = new SpogTiersConfig();
		fresh.normalise();
		fresh.save();
		return fresh;
	}

	/** Fills in anything an older config file predates. */
	/** One tier element carrying an old slot's list and gamemode. */
	private static TagLayout.Element tierElement(TagLayout.Row row, TagSlot slot) {
		TagLayout.Element element = new TagLayout.Element(TagLayout.Kind.TIER, row);
		element.list(slot.list);
		element.gamemode = slot.gamemode;
		return element;
	}

	/** A separator carrying whatever the old single separator setting was. */
	private TagLayout.Element separatorElement() {
		TagLayout.Element element =
				new TagLayout.Element(TagLayout.Kind.SEPARATOR, TagLayout.Row.MIDDLE);
		element.character = showSeparators ? "|" : " ";
		element.colour = 0x555555;
		return element;
	}

	/**
	 * Repairs a config read from somewhere less trusted than our own writer.
	 *
	 * <p>Package-visible rather than private because an imported layout code
	 * arrives in the same state a hand-edited file does: possibly missing the
	 * name element, possibly naming a list this build does not have.
	 */
	void normalise() {
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
		// Carried across from the old switch, which only knew "before the
		// name": that is the bottom-left slot now. A config that predates
		// either setting has neither, and gets the same default as a new one.
		// Built from the old three slots on first run after the upgrade, so
		// the tag someone had is the tag they keep. Their order was fixed:
		// above on its own row, then left, the name, and right.
		if (tagLayout == null) {
			tagLayout = new TagLayout();
			if (aboveTag != null && aboveTag.enabled) {
				tagLayout.elements.add(tierElement(TagLayout.Row.TOP, aboveTag));
			}
			if (leftTag != null && leftTag.enabled) {
				tagLayout.elements.add(tierElement(TagLayout.Row.MIDDLE, leftTag));
				tagLayout.elements.add(separatorElement());
			}
			tagLayout.elements.add(
					new TagLayout.Element(TagLayout.Kind.NAME, TagLayout.Row.MIDDLE));
			if (rightTag != null && rightTag.enabled) {
				tagLayout.elements.add(separatorElement());
				tagLayout.elements.add(tierElement(TagLayout.Row.MIDDLE, rightTag));
			}
		}
		tagLayout.normalise();

		// Nothing sets this false any more, and leaving an old config's false
		// in place would hide the door tier with no way in the UI to bring it
		// back.
		extraTierlists = true;

		if (regionSlot == null) {
			regionSlot = Boolean.TRUE.equals(showRegionOnNametag)
					? RegionSlot.BOTTOM_LEFT
					: RegionSlot.OFF;
		}
		showRegionOnNametag = null;
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
			try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException e) {
			SpogTiers.LOGGER.error("Could not write config", e);
		}
	}
}
