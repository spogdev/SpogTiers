package dev.spog.tiers;

import dev.spog.tiers.config.SpogTiersConfig;
import dev.spog.tiers.data.NameIndex;
import dev.spog.tiers.data.TierCache;
import dev.spog.tiers.data.TierService;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public class SpogTiersClient implements ClientModInitializer {
	/**
	 * How often the name index is written, in client ticks.
	 *
	 * <p>Saved on quit as well, but not only on quit: the index is built up
	 * over a session of play and a crash would otherwise throw away
	 * everything learned since launch. Five minutes of ticks, and the save
	 * is a no-op unless something was actually recorded.
	 */
	private static final int INDEX_SAVE_TICKS = 20 * 60 * 5;

	private static SpogTiersConfig config;
	private static TierCache cache;
	private static TierService service;

	private static int sinceIndexSave;

	@Override
	public void onInitializeClient() {
		config = SpogTiersConfig.load();
		cache = new TierCache(config);
		service = new TierService(config, cache);

		// Read before anything can record into it, so a name learned this
		// session is not written over a file that was never loaded.
		NameIndex.load();

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			service.tick();
			dev.spog.tiers.client.QuickTiers.tick(client);
			if (++sinceIndexSave >= INDEX_SAVE_TICKS) {
				sinceIndexSave = 0;
				NameIndex.save();
			}
		});

		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> NameIndex.save());

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
