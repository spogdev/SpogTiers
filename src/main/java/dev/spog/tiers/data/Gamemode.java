package dev.spog.tiers.data;

import java.util.Locale;

/**
 * PvP gamemodes across the supported tier lists.
 *
 * <p>Keys differ per provider for the same mode (PvPTiers uses {@code neth_pot}
 * where PVPHQ uses {@code nethpot}), so {@link #byKey(String)} accepts every
 * spelling observed in the wild.
 */
public enum Gamemode {
	SWORD("sword", "Sword", 0xFF5AC8FA),
	UHC("uhc", "UHC", 0xFFFF5555),
	POT("pot", "Pot", 0xFFFF77C6),
	NETH_POT("neth_pot", "Neth Pot", 0xFFA974FF),
	SMP("smp", "SMP", 0xFF3FD1B5),
	AXE("axe", "Axe", 0xFFFFAA00),
	MACE("mace", "Mace", 0xFFC0C0C0),
	VANILLA("vanilla", "Vanilla", 0xFF7BE07B),
	DIA_SMP("dia_smp", "Dia SMP", 0xFF66DDEE),
	ELYTRA("elytra", "Elytra", 0xFFE8E8E8),
	BEDWARS("bedwars", "Bedwars", 0xFFFF9F45),
	CART("cart", "Cart", 0xFFC98F4F),
	DIA_CRYSTAL("dia_crystal", "Dia Crystal", 0xFF9AA8FF),
	SPEAR_MACE("spear_mace", "Spear Mace", 0xFFD8B4FE),
	BED("bed", "Bed", 0xFFFF6B6B),
	BOW("bow", "Bow", 0xFFB5E48C),
	CREEPER("creeper", "Creeper", 0xFF52B788),
	DEBUFF("debuff", "Debuff", 0xFFE07BE0),
	MANHUNT("manhunt", "Manhunt", 0xFFFFB703),
	MINECART("minecart", "Minecart", 0xFFB98A5A),
	OG_VANILLA("og_vanilla", "OGV", 0xFF95D5B2),
	SPEED("speed", "Speed", 0xFF48CAE4),
	TRIDENT("trident", "Trident", 0xFF00B4D8),
	BRIDGE("bridge", "Bridge", 0xFF7FB7E8),
	// MCPvP ranks phases of a fight rather than a kit, so these have no
	// equivalent on the other lists.
	EARLY_GAME("early_game", "Early Game", 0xFF9BE39B),
	LATE_GAME("late_game", "Late Game", 0xFFE0A05A),
	END_GAME("end_game", "End Game", 0xFFB98AE8),
	SHIELD("shield", "Shield", 0xFFC9A227);

	private final String key;
	private final String displayName;
	private final int accent;

	Gamemode(String key, String displayName, int accent) {
		this.key = key;
		this.displayName = displayName;
		this.accent = accent;
	}

	public String key() {
		return key;
	}

	public String displayName() {
		return displayName;
	}

	/** Icon/label tint used in the profile panel. */
	public int accent() {
		return accent;
	}

	/**
	 * Resolves a provider's gamemode key. Normalises separators so
	 * {@code neth_pot}, {@code nethpot} and {@code neth-pot} all match.
	 */
	/**
	 * Resolves a provider's key, letting the provider settle any clash.
	 *
	 * <p>The list is no longer needed to read a key -- MCPvP's spear and
	 * CatPVP's spear mace are close enough to be one mode, and each card in
	 * the profile is built from its own list, so they cannot collide there.
	 * Kept so callers that know their list need not care.
	 */
	public static Gamemode byKey(TierList list, String key) {
		return byKey(key);
	}

	public static Gamemode byKey(String key) {
		if (key == null) {
			return null;
		}
		String needle = normalise(key);
		for (Gamemode mode : values()) {
			if (normalise(mode.key).equals(needle)) {
				return mode;
			}
		}
		// Aliases that do not simply differ by separator.
		return switch (needle) {
			case "nethop", "netherite", "netheritepot", "nethpot", "npot" -> NETH_POT;
			// The same mode under four names. Vanilla wins because three lists
			// call it that -- MCTiers, PVPHQ and CatPVP -- against PvPTiers
			// alone saying crystal; PVPHQ declares crystal too but has never
			// returned it, so its vanilla is the one that exists.
			case "crystal", "crystalpvp" -> VANILLA;
			// MCPvP's name for axe.
			case "shield" -> AXE;
			// CatPVP's name for sword. Its artwork stays its own: the icon
			// follows whichever list the tier came from, so a CatPVP row still
			// shows the beast.
			case "beast" -> SWORD;
			case "diamondsmp", "diasmp" -> DIA_SMP;
			case "htcart", "hightiercart" -> CART;
			case "diamondcrystal" -> DIA_CRYSTAL;
			case "potion" -> POT;
			// Both lists' spear kits, which are alike enough to be one mode.
			case "spear", "mcpvpspear" -> SPEAR_MACE;
			// MCPvP spells its phases with a hyphen, which normalise() drops.
			case "earlygame" -> EARLY_GAME;
			case "lategame" -> LATE_GAME;
			case "endgame" -> END_GAME;
			default -> null;
		};
	}

	private static String normalise(String raw) {
		return raw.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "").replace(" ", "");
	}
}
