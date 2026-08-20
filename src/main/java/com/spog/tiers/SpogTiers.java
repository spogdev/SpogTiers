package com.spog.tiers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared constants for the mod. Kept free of Minecraft imports so it is
 * trivially portable between version branches.
 */
public final class SpogTiers {
	public static final String MOD_ID = "spogtiers";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private SpogTiers() {
	}
}
