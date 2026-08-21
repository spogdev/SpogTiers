package com.spog.tiers.data;

import java.util.Locale;

/**
 * PvP gamemodes across the supported tier lists.
 *
 * <p>Keys differ per provider for the same mode (PvPTiers uses {@code neth_pot}
 * where PVPHQ uses {@code nethpot}), so {@link #byKey(String)} accepts every
 * spelling observed in the wild.
 */
public enum Gamemode {
	CRYSTAL("crystal", "Crystal", 0xFFD16BFF),
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
	OG_VANILLA("og_vanilla", "OG Vanilla", 0xFF95D5B2),
	SPEED("speed", "Speed", 0xFF48CAE4),
	TRIDENT("trident", "Trident", 0xFF00B4D8),
	BEAST("beast", "Beast", 0xFFE0685A),
	BRIDGE("bridge", "Bridge", 0xFF7FB7E8);

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
			case "nethop", "netherite", "nethpot", "npot" -> NETH_POT;
			case "diamondsmp", "diasmp" -> DIA_SMP;
			case "crystalpvp" -> CRYSTAL;
			case "htcart", "hightiercart" -> CART;
			case "diamondcrystal" -> DIA_CRYSTAL;
			case "potion" -> POT;
			case "spear" -> SPEAR_MACE;
			default -> null;
		};
	}

	private static String normalise(String raw) {
		return raw.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "").replace(" ", "");
	}
}
