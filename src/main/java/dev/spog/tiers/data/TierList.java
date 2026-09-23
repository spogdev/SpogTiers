package dev.spog.tiers.data;

import java.util.ArrayList;
import java.util.List;

/**
 * The tier lists SpogTiers can pull from.
 *
 * <p>Endpoints were verified live; none of these services publish API docs, so
 * treat the paths here as observed behaviour rather than a contract. The first
 * three share one JSON schema; PVPHQ has its own (see {@code TierService}).
 */
public enum TierList {
	PVPHQ("pvphq", "PVPHQ", "https://pvphq.com/api/v1/players/", true),
	PVPTIERS("pvptiers", "PvPTiers", "https://pvptiers.com/api/profile/", false),
	// v2 with tests asked for, as MCTiers below: SubTiers runs the same API
	// software, down to the endpoint list and the response shapes, so the same
	// flag gets the same tester data.
	SUBTIERS("subtiers", "SubTiers", "https://subtiers.net/api/v2/profile/", false, "?tests"),
	// v2, with tests asked for: v1 was deprecated with a stated removal date of
	// 1 June 2026, and only v2 publishes who tested a placement. The flag takes
	// no value, which is why it hangs off the end of the path.
	MCTIERS("mctiers", "MCTiers", "https://mctiers.com/api/v2/profile/", false, "?tests"),
	MCPVP("mcpvp", "MCPvP", "https://www.mcpvp.com/tiers/search", false),
	CATPVP("catpvp", "CatPVP", "https://catpvp.net/player/", true);

	private final String key;
	private final String displayName;
	private final String endpoint;
	private final boolean dashedUuid;
	/** Appended after the uuid, for a list whose request carries a flag. */
	private final String suffix;

	TierList(String key, String displayName, String endpoint, boolean dashedUuid) {
		this(key, displayName, endpoint, dashedUuid, "");
	}

	TierList(String key, String displayName, String endpoint, boolean dashedUuid,
			String suffix) {
		this.key = key;
		this.displayName = displayName;
		this.endpoint = endpoint;
		this.dashedUuid = dashedUuid;
		this.suffix = suffix;
	}

	/** What follows the uuid in a request to this list, usually nothing. */
	public String suffix() {
		return suffix;
	}

	public String key() {
		return key;
	}

	public String displayName() {
		return displayName;
	}

	public String endpoint() {
		return endpoint;
	}

	/** PVPHQ rejects undashed UUIDs with a 400; the others expect them undashed. */
	public boolean usesDashedUuid() {
		return dashedUuid;
	}

	/** PVPHQ returns a different payload shape and needs its own parser. */
	public boolean isPvpHq() {
		return this == PVPHQ;
	}

	/** MCPvP has no UUID route; it is searched by name. See {@code TierService}. */
	public boolean isMcPvp() {
		return this == MCPVP;
	}

	/** CatPVP ranks by Elo with named ranks, and needs its own parser. */
	public boolean isCatPvp() {
		return this == CATPVP;
	}

	/**
	 * True when the list publishes an overall standing we can badge.
	 *
	 * <p>Every list does, though by different means: MCTiers, PvPTiers and
	 * SubTiers put it in the profile as {@code overall} and MCPvP as
	 * {@code rank}, so those cost nothing extra, while PVPHQ and CatPVP need
	 * their leaderboards read.
	 */
	public boolean publishesRanks() {
		return true;
	}

	/** True when the standing comes back with the profile, needing no lookup. */
	public boolean rankInProfile() {
		return this != PVPHQ && this != CATPVP;
	}


	/**
	 * True when the endpoint takes a player name rather than a UUID, and so
	 * needs the name resolved before a lookup can be made.
	 */
	public boolean usesNameLookup() {
		return this == MCPVP;
	}

	/** The site's logo, bundled under assets/spogtiers/textures/gui. */
	public String logoPath() {
		return "textures/gui/" + key + ".png";
	}

	/**
	 * The gamemodes this list actually ranks.
	 *
	 * <p>Drives the settings dropdowns, so a user can never pick something the
	 * list does not have (PvPTiers has no Bed, for instance).
	 *
	 * <p>Taken from what the APIs actually return, not from the icons each site
	 * ships: SubTiers serves mace, elytra and dia_crystal artwork but ranks
	 * nobody in them, so offering those would be a dead end.
	 */
	public List<Gamemode> gamemodes() {
		return switch (this) {
			case MCTIERS -> modes("axe", "mace", "neth_pot", "pot", "smp", "sword",
					"uhc", "vanilla");
			// No "crystal": PVPHQ declares one but has never returned it, and
			// its vanilla is the same mode the other lists call vanilla.
			case PVPHQ -> modes("axe", "cart", "dia_smp", "mace", "neth_pot",
					"pot", "smp", "spear_mace", "sword", "uhc", "vanilla");
			// PvPTiers says "crystal" for what everyone else calls vanilla; the
			// key resolves to VANILLA, so the mode is named here as it lands.
			case PVPTIERS -> modes("axe", "mace", "neth_pot", "pot", "smp",
					"sword", "uhc", "vanilla");
			case SUBTIERS -> modes("bed", "bow", "creeper", "debuff", "dia_crystal",
					"dia_smp", "elytra", "manhunt", "minecart", "og_vanilla",
					"speed", "trident");
			// Straight from MCPvP's own kit list: five kits and three phases of
			// a fight. It ranks neither axe nor the SMP modes the other lists
			// do, and the phases have no equivalent anywhere else.
			// "shield" is MCPvP's name for axe, so it is listed as axe.
			case MCPVP -> modes("axe", "early_game", "end_game", "late_game", "mace",
					"spear_mace", "pot", "sword");
			case CATPVP -> modes("axe", "sword", "bow", "bridge", "cart", "creeper",
					"dia_smp", "mace", "neth_pot", "pot", "smp", "spear_mace",
					"uhc", "vanilla");
		};
	}

	private static List<Gamemode> modes(String... keys) {
		List<Gamemode> out = new ArrayList<>(keys.length);
		for (String key : keys) {
			Gamemode mode = Gamemode.byKey(key);
			if (mode != null) {
				out.add(mode);
			}
		}
		return List.copyOf(out);
	}

	/**
	 * This list's own icon for a gamemode. Each site draws its own artwork, so
	 * a row shows the icon of whichever list it came from. Returns null when
	 * the list ships no icon for that mode.
	 */
	public String modeIconPath(String modeKey) {
		return "textures/gui/modes/" + key + "/" + artworkKey(modeKey) + ".png";
	}

	/**
	 * The file this list stores a mode's artwork under.
	 *
	 * <p>Two lists have their own name for a mode everyone else shares:
	 * MCPvP's axe is "shield", and PvPTiers calls vanilla "crystal". Both
	 * resolve to one {@link Gamemode}, so a lookup arrives under the shared
	 * name while the file is still filed under the list's own -- asking for
	 * pvptiers/vanilla.png finds nothing, and the row drew no icon at all.
	 *
	 * <p>Renaming the files was the alternative and is worse: the font glyphs
	 * are indexed by sorted position, so one rename renumbers every codepoint
	 * that sorts after it.
	 */
	private String artworkKey(String modeKey) {
		return switch (this) {
			case MCPVP -> switch (modeKey) {
				case "axe" -> "shield";
				// MCPvP draws its own spear, so a row from it keeps that
				// rather than CatPVP's spear mace.
				case "spear_mace" -> "mcpvp_spear";
				default -> modeKey;
			};
			case PVPTIERS -> modeKey.equals("vanilla") ? "crystal" : modeKey;
			// CatPVP ranks sword as beast and ships that artwork, so a row
			// sourced from it keeps the beast even though the mode is now
			// sword everywhere else.
			case CATPVP -> modeKey.equals("sword") ? "beast" : modeKey;
			default -> modeKey;
		};
	}
}
