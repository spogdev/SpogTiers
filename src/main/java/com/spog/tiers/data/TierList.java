package com.spog.tiers.data;

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
	SUBTIERS("subtiers", "SubTiers", "https://subtiers.net/api/profile/", false),
	MCTIERS("mctiers", "MCTiers", "https://mctiers.com/api/profile/", false),
	MCPVP("mcpvp", "MCPvP", "https://www.mcpvp.com/tiers/search", false),
	CATPVP("catpvp", "CatPVP", "https://catpvp.net/player/", true);

	private final String key;
	private final String displayName;
	private final String endpoint;
	private final boolean dashedUuid;

	TierList(String key, String displayName, String endpoint, boolean dashedUuid) {
		this.key = key;
		this.displayName = displayName;
		this.endpoint = endpoint;
		this.dashedUuid = dashedUuid;
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
			case PVPHQ -> modes("axe", "cart", "crystal", "dia_smp", "mace", "neth_pot",
					"pot", "smp", "spear_mace", "sword", "uhc", "vanilla");
			case PVPTIERS -> modes("axe", "crystal", "mace", "neth_pot", "pot", "smp",
					"sword", "uhc");
			case SUBTIERS -> modes("bed", "bow", "creeper", "debuff", "dia_smp",
					"manhunt", "minecart", "og_vanilla", "speed", "trident");
			case MCPVP -> modes("axe", "mace", "neth_pot", "pot", "smp", "sword",
					"uhc", "vanilla");
			case CATPVP -> modes("axe", "beast", "bow", "bridge", "cart", "creeper",
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
		return "textures/gui/modes/" + key + "/" + modeKey + ".png";
	}
}
