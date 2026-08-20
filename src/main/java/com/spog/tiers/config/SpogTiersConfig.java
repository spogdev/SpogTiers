package com.spog.tiers.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.spog.tiers.SpogTiers;
import com.spog.tiers.data.Gamemode;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** User-editable settings, persisted to {@code config/spogtiers.json}. */
public class SpogTiersConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Master switch for all tag rendering. */
	public boolean enabled = true;

	/** Show tags above player heads in the world. */
	public boolean showNametags = true;

	/** Show tags in the tab player list. */
	public boolean showTabList = true;

	/** Which gamemode's tier to display. */
	public Gamemode displayMode = Gamemode.VANILLA;

	/** Show the best tier across all gamemodes instead of {@link #displayMode}. */
	public boolean showBestTier = false;

	/** Base URL of the tier API. */
	public String apiBaseUrl = "https://api.example.invalid/v1";

	/** How long a cached lookup stays fresh, in seconds. */
	public int cacheTtlSeconds = 900;

	/** Max lookups dispatched per second, to stay friendly to the API. */
	public int requestsPerSecond = 5;

	public static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve(SpogTiers.MOD_ID + ".json");
	}

	public static SpogTiersConfig load() {
		Path path = path();
		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path)) {
				SpogTiersConfig loaded = GSON.fromJson(reader, SpogTiersConfig.class);
				if (loaded != null) {
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
