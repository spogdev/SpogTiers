package com.spog.tiers.data;

import com.google.gson.JsonArray;
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
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fetches tier data. Player UUIDs are queued on the client thread and drained
 * on a small worker pool at a bounded rate, so joining a busy server does not
 * fire hundreds of requests at once.
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
		this.workers = Executors.newFixedThreadPool(3, runnable -> {
			Thread thread = new Thread(runnable, "SpogTiers Lookup");
			thread.setDaemon(true);
			return thread;
		});
		this.http = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
	}

	public void tick() {
		if (!config.enabled) {
			return;
		}
		enqueueVisiblePlayers();
		drainQueue();
	}

	/** Forces a re-fetch for one player, bypassing the cache (Update button). */
	public void refresh(UUID uuid) {
		cache.invalidate(uuid);
		request(uuid);
	}

	/**
	 * Queues a lookup for any player, on this server or not. Used by
	 * {@code /tiers <player>}, where the target may be offline entirely.
	 */
	public void request(UUID uuid) {
		if (cache.needsLookup(uuid) && !queue.contains(uuid)) {
			queue.addFirst(uuid);
		}
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
		workers.submit(() -> fetchAll(uuid));
	}

	/** Queries every enabled list; one failing must not sink the others. */
	private void fetchAll(UUID uuid) {
		boolean any = false;
		for (TierList list : TierList.values()) {
			if (!config.isEnabled(list)) {
				continue;
			}
			try {
				PlayerTiers result = fetchOne(list, uuid);
				if (result != null) {
					cache.put(uuid, list, result);
					any = true;
				}
			} catch (Exception e) {
				SpogTiers.LOGGER.debug("{} lookup failed for {}", list.key(), uuid, e);
			}
		}
		if (any) {
			cache.markComplete(uuid);
		} else {
			cache.markFailed(uuid, FAILURE_BACKOFF_MILLIS);
		}
	}

	private PlayerTiers fetchOne(TierList list, UUID uuid) throws Exception {
		String id = list.usesDashedUuid() ? uuid.toString() : uuid.toString().replace("-", "");
		HttpRequest request = HttpRequest.newBuilder(URI.create(list.endpoint() + id))
				.header("Accept", "application/json")
				.header("User-Agent", "SpogTiers/1.0 (Minecraft mod)")
				.timeout(Duration.ofSeconds(10))
				.GET()
				.build();

		HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
		// 404 is the normal "not on this list" answer, not an error.
		if (response.statusCode() == 404) {
			return new PlayerTiers(list, "", System.currentTimeMillis());
		}
		if (response.statusCode() != 200) {
			throw new IllegalStateException(list.key() + " returned HTTP " + response.statusCode());
		}

		JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
		return list.isPvpHq() ? parsePvpHq(list, root) : parseStandard(list, root);
	}

	/**
	 * MCTiers, PvPTiers and SubTiers share this shape:
	 * <pre>
	 * { "name":"x", "region":"EU", "points":18, "overall":9622,
	 *   "rankings": { "sword": { "tier":4, "pos":1, "retired":false } } }
	 * </pre>
	 * {@code pos} is 0 for HT and 1 for LT.
	 */
	private PlayerTiers parseStandard(TierList list, JsonObject root) {
		PlayerTiers result = new PlayerTiers(list, string(root, "name"), System.currentTimeMillis());
		result.region(string(root, "region"));
		result.overall(intOr(root, "overall", 0));
		result.points(intOr(root, "points", 0));

		JsonElement rankings = root.get("rankings");
		if (rankings == null || !rankings.isJsonObject()) {
			return result;
		}

		for (var entry : rankings.getAsJsonObject().entrySet()) {
			if (!entry.getValue().isJsonObject()) {
				continue;
			}
			JsonObject value = entry.getValue().getAsJsonObject();
			if (!value.has("tier")) {
				continue;
			}
			Tier.Position position = intOr(value, "pos", 0) == 0
					? Tier.Position.HIGH
					: Tier.Position.LOW;
			Tier tier = new Tier(
					value.get("tier").getAsInt(),
					position,
					value.has("retired") && value.get("retired").getAsBoolean());

			Gamemode mode = Gamemode.byKey(entry.getKey());
			if (mode != null) {
				result.put(mode, tier);
			} else {
				result.putUnknown(entry.getKey(), tier);
			}
		}
		return result;
	}

	/**
	 * PVPHQ is ELO-based and hands back rendered labels and colours:
	 * <pre>
	 * { "ranked": [ { "gametype":"sword", "tier":"MT3", "tierColor":"#BF6C3D",
	 *                 "unranked":false } ] }
	 * </pre>
	 * We keep its colours so our panel matches the site exactly.
	 */
	private PlayerTiers parsePvpHq(TierList list, JsonObject root) {
		PlayerTiers result = new PlayerTiers(list, string(root, "name"), System.currentTimeMillis());

		JsonElement regions = root.get("regions");
		if (regions != null && regions.isJsonArray() && !regions.getAsJsonArray().isEmpty()) {
			result.region(regions.getAsJsonArray().get(0).getAsString());
		}

		JsonElement ranked = root.get("ranked");
		if (ranked == null || !ranked.isJsonArray()) {
			return result;
		}

		JsonArray entries = ranked.getAsJsonArray();
		for (JsonElement element : entries) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject value = element.getAsJsonObject();
			if (value.has("unranked") && value.get("unranked").getAsBoolean()) {
				continue;
			}
			Tier tier = Tier.parseLabel(string(value, "tier"), parseHexColor(string(value, "tierColor")));
			if (!tier.isRanked()) {
				continue;
			}

			String key = string(value, "gametype");
			Gamemode mode = Gamemode.byKey(key);
			if (mode != null) {
				result.put(mode, tier);
			} else {
				String label = string(value, "gametypeName");
				result.putUnknown(label.isEmpty() ? key : label, tier);
			}
		}
		return result;
	}

	/** Parses {@code "#RRGGBB"}; returns 0 when absent or malformed. */
	private static int parseHexColor(String raw) {
		if (raw == null || !raw.startsWith("#") || raw.length() != 7) {
			return 0;
		}
		try {
			return Integer.parseInt(raw.substring(1), 16);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static String string(JsonObject object, String key) {
		JsonElement element = object.get(key);
		return element == null || element.isJsonNull() ? "" : element.getAsString();
	}

	private static int intOr(JsonObject object, String key, int fallback) {
		JsonElement element = object.get(key);
		return element == null || element.isJsonNull() ? fallback : element.getAsInt();
	}

	public void shutdown() {
		workers.shutdownNow();
	}
}
