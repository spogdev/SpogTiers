package com.spog.tiers.data;

import java.util.Locale;

/** The PvP gamemodes a player can be ranked in. */
public enum Gamemode {
	VANILLA("vanilla", "Vanilla"),
	UHC("uhc", "UHC"),
	POT("pot", "Pot"),
	NETHOP("nethop", "NethOP"),
	SMP("smp", "SMP"),
	SWORD("sword", "Sword"),
	AXE("axe", "Axe"),
	MACE("mace", "Mace");

	private final String key;
	private final String displayName;

	Gamemode(String key, String displayName) {
		this.key = key;
		this.displayName = displayName;
	}

	public String key() {
		return key;
	}

	public String displayName() {
		return displayName;
	}

	public static Gamemode byKey(String key) {
		String needle = key.toLowerCase(Locale.ROOT);
		for (Gamemode mode : values()) {
			if (mode.key.equals(needle)) {
				return mode;
			}
		}
		return null;
	}
}
