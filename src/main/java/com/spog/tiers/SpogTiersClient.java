package com.spog.tiers;

import com.spog.tiers.config.SpogTiersConfig;
import com.spog.tiers.data.TierCache;
import com.spog.tiers.data.TierService;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public class SpogTiersClient implements ClientModInitializer {
	private static SpogTiersConfig config;
	private static TierCache cache;
	private static TierService service;

	@Override
	public void onInitializeClient() {
		config = SpogTiersConfig.load();
		cache = new TierCache(config);
		service = new TierService(config, cache);

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			service.tick();
			com.spog.tiers.client.QuickTiers.tick(client);
		});

		SpogTiers.LOGGER.info("SpogTiers initialised");
	}

	public static SpogTiersConfig config() {
		return config;
	}

	public static TierCache cache() {
		return cache;
	}

	public static TierService service() {
		return service;
	}
}
