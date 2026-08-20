package com.spog.tiers.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.spog.tiers.SpogTiers;
import com.spog.tiers.config.SpogTiersConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Drives tier lookups. Player UUIDs are queued on the client thread, then
 * drained on a small worker pool at a bounded rate so the API is never flooded
 * when joining a busy server.
 */
public class TierService {
	private static final long FAILURE_BACKOFF_MILLIS = 60_000L;

	private final SpogTiersConfig config;
	private final TierCache cache;
	private final Deque<UUID> queue = new ArrayDeque<>();
	private final ExecutorService workers;
	private final HttpClient http;

	private long lastDispatchMillis;

	public TierService(SpogTiersConfig config, TierCache cache) {
		this.config = config;
		this.cache = cache;
		this.workers = Executors.newFixedThreadPool(2, runnable -> {
			Thread thread = new Thread(runnable, "SpogTiers Lookup");
			thread.setDaemon(true);
			return thread;
		});
		this.http = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.build();
	}

	/** Called every client tick: enqueues visible players and drains the queue. */
	public void tick() {
		if (!config.enabled) {
			return;
		}
		enqueueVisiblePlayers();
		drainQueue();
	}

	private void enqueueVisiblePlayers() {
		Minecraft client = Minecraft.getInstance();
		if (client.getConnection() == null) {
			return;
		}
		for (PlayerInfo entry : client.getConnection().getOnlinePlayers()) {
			UUID uuid = entry.getProfile().id();
			if (uuid != null && cache.needsLookup(uuid) && !queue.contains(uuid)) {
				queue.add(uuid);
			}
		}
	}

	private void drainQueue() {
		if (queue.isEmpty()) {
			return;
		}
		long now = System.currentTimeMillis();
		long minGap = 1000L / Math.max(1, config.requestsPerSecond);
		if (now - lastDispatchMillis < minGap) {
			return;
		}
		UUID uuid = queue.poll();
		if (uuid == null || !cache.needsLookup(uuid)) {
			return;
		}
		lastDispatchMillis = now;
		cache.markPending(uuid);
		workers.submit(() -> fetch(uuid));
	}

	private void fetch(UUID uuid) {
		try {
			String url = config.apiBaseUrl + "/tiers/" + uuid.toString().replace("-", "");
			HttpRequest request = HttpRequest.newBuilder(URI.create(url))
					.header("Accept", "application/json")
					.header("User-Agent", "SpogTiers")
					.timeout(Duration.ofSeconds(10))
					.GET()
					.build();

			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				cache.markFailed(uuid, FAILURE_BACKOFF_MILLIS);
				return;
			}
			cache.put(uuid, parse(uuid, response.body()));
		} catch (Exception e) {
			SpogTiers.LOGGER.debug("Tier lookup failed for {}", uuid, e);
			cache.markFailed(uuid, FAILURE_BACKOFF_MILLIS);
		}
	}

	/**
	 * Parses the API payload. Expected shape:
	 * <pre>
	 * { "name": "Notch", "rankings": { "vanilla": { "tier": 2, "pos": "HT", "retired": false } } }
	 * </pre>
	 * Unknown gamemodes and malformed entries are skipped rather than failing
	 * the whole lookup.
	 */
	private PlayerTiers parse(UUID uuid, String body) {
		JsonObject root = JsonParser.parseString(body).getAsJsonObject();
		String name = root.has("name") ? root.get("name").getAsString() : uuid.toString();
		PlayerTiers result = new PlayerTiers(name, System.currentTimeMillis());

		JsonElement rankings = root.get("rankings");
		if (rankings == null || !rankings.isJsonObject()) {
			return result;
		}

		for (var entry : rankings.getAsJsonObject().entrySet()) {
			Gamemode mode = Gamemode.byKey(entry.getKey());
			if (mode == null || !entry.getValue().isJsonObject()) {
				continue;
			}
			JsonObject value = entry.getValue().getAsJsonObject();
			if (!value.has("tier")) {
				continue;
			}
			int tier = value.get("tier").getAsInt();
			boolean high = !value.has("pos")
					|| value.get("pos").getAsString().toLowerCase(Locale.ROOT).startsWith("h");
			boolean retired = value.has("retired") && value.get("retired").getAsBoolean();
			result.put(mode, new Tier(tier, high, retired));
		}
		return result;
	}

	public void shutdown() {
		workers.shutdownNow();
	}
}
