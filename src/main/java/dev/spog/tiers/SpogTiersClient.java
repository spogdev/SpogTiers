package dev.spog.tiers;

import dev.spog.stats.ModStats;
import dev.spog.stats.SpogStats;
import dev.spog.tiers.config.SpogTiersConfig;
import dev.spog.tiers.data.TierCache;
import dev.spog.tiers.data.TierService;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

public class SpogTiersClient implements ClientModInitializer {
	private static SpogTiersConfig config;
	private static TierCache cache;
	private static TierService service;
	private static ModStats stats;

	@Override
	public void onInitializeClient() {
		config = SpogTiersConfig.load();
		cache = new TierCache(config);
		service = new TierService(config, cache);
		stats = startStats();

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (!allowed()) {
				return;
			}
			service.tick();
			dev.spog.tiers.client.QuickTiers.tick(client);
		});

		SpogTiers.LOGGER.info("SpogTiers initialised");
	}

	/**
	 * Whether the SpogStats dashboard lets this player use the mod. When it
	 * does not, the mod goes quiet: no tags, no lookups, and its commands fall
	 * through to the server as if the mod were not installed.
	 */
	public static boolean allowed() {
		return stats == null || stats.allowed();
	}

	/** Reports this player to the dashboard; the account is proven to it through Mojang. */
	private static ModStats startStats() {
		FabricLoader loader = FabricLoader.getInstance();
		String version = loader.getModContainer(SpogTiers.MOD_ID)
				.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
		String mcVersion = loader.getModContainer("minecraft")
				.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
		return SpogStats.mod(SpogTiers.MOD_ID)
				.attr("modVersion", version)
				.attr("mcVersion", mcVersion)
				.attr("loader", "Fabric")
				.attr("scope", SpogTiersClient::scope)
				.profileName(() -> Minecraft.getInstance().getUser().getName())
				.joinServer(serverId -> {
					Minecraft mc = Minecraft.getInstance();
					mc.services().sessionService().joinServer(
							mc.getUser().getProfileId(), mc.getUser().getAccessToken(), serverId);
				})
				.cacheFile(loader.getConfigDir().resolve("spogstats/" + SpogTiers.MOD_ID + ".json"))
				.start();
	}

	/**
	 * Where the player is, sent with every beat so the dashboard counts only
	 * server time as online. The beats themselves never stop: they also carry
	 * the access rules, which apply in the menu and singleplayer too. Names
	 * match TeamLocator's scopes, with "server" for any multiplayer server.
	 */
	private static String scope() {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null) {
			return "menu";
		}
		if (mc.isLocalServer()) {
			return "singleplayer";
		}
		return mc.getConnection() != null ? "server" : "menu";
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
