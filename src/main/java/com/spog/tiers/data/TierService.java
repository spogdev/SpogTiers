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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fetches tier data. Player UUIDs are queued on the client thread and drained
 * on a small worker pool at a bounded rate, so joining a busy server does not
 * fire hundreds of requests at once.
 */
public class TierService {
	private static final long FAILURE_BACKOFF_MILLIS = 60_000L;
	private static final String SESSION_PROFILE =
			"https://sessionserver.mojang.com/session/minecraft/profile/";
	/**
	 * Mojang removed its own name-history endpoint in 2022; laby.net kept the
	 * records it had gathered before then and still tracks changes since.
	 */
	private static final String NAME_HISTORY = "https://laby.net/api/v3/user/";

	private final SpogTiersConfig config;
	private final TierCache cache;
	private final Deque<UUID> queue = new ArrayDeque<>();
	private final ExecutorService workers;
	private final HttpClient http;
	/** UUID to name, for the lists that can only be searched by name. */
	private final Map<UUID, String> names = new ConcurrentHashMap<>();
	/** Past names per player, fetched on demand by the profile screen. */
	private final Map<UUID, NameHistory> nameHistory = new ConcurrentHashMap<>();
	private final Set<UUID> nameHistoryPending = ConcurrentHashMap.newKeySet();

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
		if (list.usesNameLookup()) {
			return fetchByName(list, uuid);
		}
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
	 * Looks a player up on a list that only searches by name.
	 *
	 * <p>MCPvP exposes no UUID route, so the name is resolved first and the
	 * results are then matched back on UUID -- the search is a prefix match and
	 * happily returns other players whose names merely start the same way, so
	 * picking by name alone would tag the wrong person.
	 */
	private PlayerTiers fetchByName(TierList list, UUID uuid) throws Exception {
		String name = resolveName(uuid);
		if (name == null || name.isEmpty()) {
			return new PlayerTiers(list, "", System.currentTimeMillis());
		}

		String query = list.endpoint() + "?q="
				+ URLEncoder.encode(name, StandardCharsets.UTF_8)
				+ "&kit=overall&include_retired=1";

		HttpRequest request = HttpRequest.newBuilder(URI.create(query))
				.header("Accept", "application/json")
				.header("User-Agent", "SpogTiers/1.0 (Minecraft mod)")
				.timeout(Duration.ofSeconds(10))
				.GET()
				.build();

		HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() == 404) {
			return new PlayerTiers(list, "", System.currentTimeMillis());
		}
		if (response.statusCode() != 200) {
			throw new IllegalStateException(list.key() + " returned HTTP " + response.statusCode());
		}

		JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
		JsonElement players = root.get("players");
		if (players == null || !players.isJsonArray()) {
			return new PlayerTiers(list, name, System.currentTimeMillis());
		}

		String wanted = uuid.toString().replace("-", "");
		for (JsonElement element : players.getAsJsonArray()) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject player = element.getAsJsonObject();
			if (string(player, "uuid").replace("-", "").equalsIgnoreCase(wanted)) {
				return parseMcPvp(list, player);
			}
		}
		// The search worked, this player simply is not ranked on the list.
		return new PlayerTiers(list, name, System.currentTimeMillis());
	}

	/**
	 * UUID to current name, via the session server.
	 *
	 * <p>The tab list is checked first: for anyone on this server the name is
	 * already to hand and costs nothing. Results are memoised, because names
	 * change rarely and every refresh would otherwise re-ask.
	 */
	private String resolveName(UUID uuid) {
		String cached = names.get(uuid);
		if (cached != null) {
			return cached;
		}

		String online = onlineName(uuid);
		if (online != null) {
			names.put(uuid, online);
			return online;
		}

		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(
							SESSION_PROFILE + uuid.toString().replace("-", "")))
					.header("Accept", "application/json")
					.header("User-Agent", "SpogTiers/1.0 (Minecraft mod)")
					.timeout(Duration.ofSeconds(10))
					.GET()
					.build();

			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200 || response.body().isBlank()) {
				return null;
			}
			JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
			String name = string(root, "name");
			if (!name.isEmpty()) {
				names.put(uuid, name);
			}
			return name;
		} catch (Exception e) {
			SpogTiers.LOGGER.debug("Name lookup failed for {}", uuid, e);
			return null;
		}
	}

	/** The name from the tab list, or null when that player is not connected. */
	private static String onlineName(UUID uuid) {
		Minecraft client = Minecraft.getInstance();
		if (client.getConnection() == null) {
			return null;
		}
		for (PlayerInfo info : client.getConnection().getOnlinePlayers()) {
			if (uuid.equals(info.getProfile().id())) {
				String name = info.getProfile().name();
				return name == null || name.isEmpty() ? null : name;
			}
		}
		return null;
	}

	/**
	 * MCPvP hands back a whole search row per player, with the tiers flattened
	 * into parallel maps:
	 * <pre>
	 * { "uuid":"..", "name":"x", "points":316, "rank":2, "region":"EU",
	 *   "kitRanks":     { "sword":"LT1" },
	 *   "kitPeakRanks": { "sword":"HT1" },
	 *   "kitRetired":   { "sword":true } }
	 * </pre>
	 */
	private PlayerTiers parseMcPvp(TierList list, JsonObject root) {
		PlayerTiers result = new PlayerTiers(list, string(root, "name"), System.currentTimeMillis());
		result.region(string(root, "region"));
		result.overall(intOr(root, "rank", 0));
		result.points(intOr(root, "points", 0));

		JsonElement ranks = root.get("kitRanks");
		if (ranks == null || !ranks.isJsonObject()) {
			return result;
		}
		JsonObject peaks = object(root, "kitPeakRanks");
		JsonObject retired = object(root, "kitRetired");

		for (var entry : ranks.getAsJsonObject().entrySet()) {
			if (entry.getValue().isJsonNull()) {
				continue;
			}
			String key = entry.getKey();
			Tier parsed = Tier.parseLabel(entry.getValue().getAsString(), 0);
			if (!parsed.isRanked()) {
				continue;
			}
			boolean isRetired = retired.has(key)
					&& !retired.get(key).isJsonNull()
					&& retired.get(key).getAsBoolean();
			Tier tier = new Tier(parsed.tier(), parsed.position(), isRetired);

			Gamemode mode = Gamemode.byKey(key);
			String label = mode != null ? mode.displayName() : key;
			if (mode != null) {
				result.put(mode, tier);
			} else {
				result.putUnknown(key, tier);
			}

			// Only a genuinely higher peak is worth showing.
			Tier peak = null;
			if (peaks.has(key) && !peaks.get(key).isJsonNull()) {
				Tier candidate = Tier.parseLabel(peaks.get(key).getAsString(), 0);
				if (candidate.isRanked() && isBetter(candidate, tier)) {
					peak = candidate;
				}
			}
			result.detail(label, new TierDetail(0L, 0, 0, 0, 0, "", 0, peak));
		}
		return result;
	}

	/** True when {@code candidate} outranks {@code current}. */
	private static boolean isBetter(Tier candidate, Tier current) {
		if (candidate.tier() != current.tier()) {
			return candidate.tier() < current.tier();
		}
		return candidate.position().ordinal() < current.position().ordinal();
	}

	/** A nested object, or an empty one when absent -- saves null checks. */
	private static JsonObject object(JsonObject parent, String key) {
		JsonElement element = parent.get(key);
		return element != null && element.isJsonObject()
				? element.getAsJsonObject()
				: new JsonObject();
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
			String label = mode != null ? mode.displayName() : entry.getKey();
			if (mode != null) {
				result.put(mode, tier);
			} else {
				result.putUnknown(entry.getKey(), tier);
			}

			Tier peak = null;
			if (value.has("peak_tier")) {
				peak = new Tier(
						value.get("peak_tier").getAsInt(),
						intOr(value, "peak_pos", 0) == 0 ? Tier.Position.HIGH : Tier.Position.LOW,
						false);
			}
			result.detail(label, new TierDetail(
					value.has("attained") ? value.get("attained").getAsLong() : 0L,
					0, 0, 0, 0, "", 0, peak));
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
			String label;
			if (mode != null) {
				result.put(mode, tier);
				label = mode.displayName();
			} else {
				label = string(value, "gametypeName");
				label = label.isEmpty() ? key : label;
				result.putUnknown(label, tier);
			}

			result.detail(label, new TierDetail(
					0L,
					intOr(value, "rating", 0),
					intOr(value, "peakRating", 0),
					intOr(value, "tierFloor", 0),
					intOr(value, "tierCeiling", 0),
					string(value, "nextTier"),
					intOr(value, "peakTr", 0),
					Tier.parseLabel(string(value, "peakTier"), 0)));
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

	/** The name history for a player, or null until it has been fetched. */
	public NameHistory nameHistory(UUID uuid) {
		return nameHistory.get(uuid);
	}

	/**
	 * Fetches a player's past names, once per player per session.
	 *
	 * <p>Only the profile screen wants these, so unlike tiers they are pulled on
	 * demand rather than for everyone in the tab list. The result is cached even
	 * when empty, so a player with no history is not re-requested every time the
	 * screen opens.
	 */
	public void requestNameHistory(UUID uuid) {
		if (uuid == null || nameHistory.containsKey(uuid) || !nameHistoryPending.add(uuid)) {
			return;
		}
		workers.submit(() -> {
			try {
				nameHistory.put(uuid, fetchNameHistory(uuid));
			} catch (Exception e) {
				SpogTiers.LOGGER.debug("Name history failed for {}", uuid, e);
				// Cache the failure too, so the screen settles on "no history"
				// instead of retrying on every frame.
				nameHistory.put(uuid, NameHistory.EMPTY);
			} finally {
				nameHistoryPending.remove(uuid);
			}
		});
	}

	/**
	 * laby.net returns the names oldest first:
	 * <pre>
	 * [ { "name":"old",     "changed_at": null },
	 *   { "name":"current", "changed_at": "2022-08-02T17:16:22+00:00" } ]
	 * </pre>
	 * The first entry is the original name and is undated, because Mojang never
	 * recorded when accounts were created. We reverse it to newest first, which
	 * is the order the panel reads in.
	 */
	private NameHistory fetchNameHistory(UUID uuid) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create(
						NAME_HISTORY + uuid.toString().replace("-", "") + "/names"))
				.header("Accept", "application/json")
				.header("User-Agent", "SpogTiers/1.0 (Minecraft mod)")
				.timeout(Duration.ofSeconds(10))
				.GET()
				.build();

		HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200 || response.body().isBlank()) {
			return NameHistory.EMPTY;
		}

		JsonElement parsed = JsonParser.parseString(response.body());
		if (!parsed.isJsonArray()) {
			return NameHistory.EMPTY;
		}

		List<NameHistory.Entry> entries = new ArrayList<>();
		for (JsonElement element : parsed.getAsJsonArray()) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject object = element.getAsJsonObject();
			String name = string(object, "name");
			if (name.isEmpty()) {
				continue;
			}
			entries.add(new NameHistory.Entry(name, epochSeconds(string(object, "changed_at"))));
		}

		Collections.reverse(entries);
		return new NameHistory(List.copyOf(entries));
	}

	/** ISO-8601 to epoch seconds; 0 when absent or unparseable. */
	private static long epochSeconds(String raw) {
		if (raw == null || raw.isEmpty()) {
			return 0L;
		}
		try {
			return OffsetDateTime.parse(raw).toEpochSecond();
		} catch (Exception e) {
			return 0L;
		}
	}

	public void shutdown() {
		workers.shutdownNow();
	}
}
