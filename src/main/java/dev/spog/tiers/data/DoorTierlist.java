package dev.spog.tiers.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.spog.tiers.SpogTiers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The whole Door SMP tierlist, for the grid {@code /tierlist} draws.
 *
 * <p>Separate from {@link TierService}, which fetches one grade at a time for a
 * nametag: this is one request for the entire roster, wanted only while the
 * screen is open. Keeping them apart means opening the screen cannot disturb
 * the per-player cache, and a badge lookup cannot be delayed behind a roster.
 *
 * <p>Retired players are dropped, as the Discord picture drops them: the
 * tierlist is about who is currently ranked.
 */
public final class DoorTierlist {

	private static final String ENDPOINT = "https://doorsmptl.spog.dev/api/v1/tierlist";

	/** How long a fetched roster is reused. Grades change rarely. */
	private static final long TTL_MILLIS = 60_000L;

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.build();

	/** Guards against a second fetch while one is already in flight. */
	private static final AtomicBoolean FETCHING = new AtomicBoolean(false);

	private static volatile Snapshot cached;
	private static volatile long fetchedAt;
	private static volatile boolean failed;

	private DoorTierlist() {
	}

	/** One player on the list. */
	public record Player(UUID uuid, String name) {
	}

	/** One tier, and everyone currently at it. */
	public record Row(String label, int colour, List<Player> players) {
	}

	/** The list as drawn: tiers in our order, empty ones left out. */
	public record Snapshot(List<Row> rows) {
	}

	/**
	 * The roster if one has been fetched, or null while it has not.
	 *
	 * <p>Does not fetch: a screen redrawing sixty times a second must not
	 * queue sixty requests. {@link #request()} is what asks.
	 */
	public static Snapshot snapshot() {
		return cached;
	}

	/**
	 * Every name on the list, for the command's tab completion.
	 *
	 * <p>Empty until a roster has been fetched, which is why the suggestion
	 * provider asks for one as it suggests: the first press of tab on a cold
	 * cache offers nothing, and the next offers everyone.
	 */
	public static List<String> names() {
		Snapshot snapshot = cached;
		if (snapshot == null) {
			return List.of();
		}
		List<String> out = new ArrayList<>();
		for (Row row : snapshot.rows()) {
			for (Player player : row.players()) {
				if (!player.name().isEmpty()) {
					out.add(player.name());
				}
			}
		}
		return out;
	}

	/** Whether the last attempt failed, so a screen can say so. */
	public static boolean failed() {
		return failed && cached == null;
	}

	/**
	 * Fetches the roster unless a fresh one is already held.
	 *
	 * <p>Safe to call repeatedly: the TTL and the in-flight flag between them
	 * mean a screen can call this on open without thinking about it.
	 */
	public static void request() {
		boolean stale = cached == null
				|| System.currentTimeMillis() - fetchedAt > TTL_MILLIS;
		if (!stale || !FETCHING.compareAndSet(false, true)) {
			return;
		}
		CompletableFuture.runAsync(DoorTierlist::fetch);
	}

	private static void fetch() {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT))
					.header("Accept", "application/json")
					.header("User-Agent", "SpogTiers/1.0 (Minecraft mod)")
					.timeout(Duration.ofSeconds(10))
					.GET()
					.build();
			HttpResponse<String> response =
					HTTP.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200 || response.body().isBlank()) {
				SpogTiers.LOGGER.debug("Tierlist returned HTTP {}", response.statusCode());
				failed = true;
				return;
			}

			Snapshot parsed = parse(JsonParser.parseString(response.body()).getAsJsonObject());
			cached = parsed;
			fetchedAt = System.currentTimeMillis();
			failed = false;
		} catch (Exception e) {
			SpogTiers.LOGGER.debug("Tierlist lookup failed", e);
			failed = true;
		} finally {
			// Always cleared, or one failure would wedge the roster as
			// un-fetchable for the rest of the session.
			FETCHING.set(false);
		}
	}

	/**
	 * Groups the served players into the served tier order.
	 *
	 * <p>Driven by the {@code tiers} array rather than by the grades that
	 * happen to be occupied, so the rows come out in the backend's order even
	 * though a map of players says nothing about it. A tier nobody holds is
	 * left out, as it is on Discord.
	 */
	private static Snapshot parse(JsonObject root) {
		Map<String, List<Player>> byTier = new LinkedHashMap<>();
		Map<String, Integer> colours = new LinkedHashMap<>();

		JsonElement tiers = root.get("tiers");
		if (tiers != null && tiers.isJsonArray()) {
			for (JsonElement element : tiers.getAsJsonArray()) {
				if (!element.isJsonObject()) {
					continue;
				}
				JsonObject tier = element.getAsJsonObject();
				String label = string(tier, "label");
				if (label.isEmpty()) {
					continue;
				}
				byTier.put(label, new ArrayList<>());
				colours.put(label, parseHex(string(tier, "color")));
			}
		}

		JsonElement players = root.get("players");
		if (players != null && players.isJsonArray()) {
			for (JsonElement element : players.getAsJsonArray()) {
				if (!element.isJsonObject()) {
					continue;
				}
				JsonObject entry = element.getAsJsonObject();
				// Retired players keep their tier on the service but are not
				// part of this picture.
				if (entry.has("retired") && entry.get("retired").getAsBoolean()) {
					continue;
				}
				String label = string(entry, "grade");
				String name = string(entry, "name");
				UUID uuid = parseUuid(string(entry, "uuid"));
				if (label.isEmpty() || uuid == null) {
					continue;
				}
				// An unknown tier still gets a row rather than losing its
				// players, so a tier added to the backend shows up here
				// without this needing to change.
				byTier.computeIfAbsent(label, key -> new ArrayList<>());
				colours.putIfAbsent(label, parseHex(string(entry, "color")));
				byTier.get(label).add(new Player(uuid, name));
			}
		}

		List<Row> rows = new ArrayList<>();
		for (Map.Entry<String, List<Player>> entry : byTier.entrySet()) {
			if (entry.getValue().isEmpty()) {
				continue;
			}
			rows.add(new Row(entry.getKey(),
					colours.getOrDefault(entry.getKey(), 0xFFFFFF),
					List.copyOf(entry.getValue())));
		}
		return new Snapshot(List.copyOf(rows));
	}

	private static String string(JsonObject object, String key) {
		JsonElement element = object.get(key);
		return element == null || element.isJsonNull() ? "" : element.getAsString();
	}

	/** A "RRGGBB" colour, or white when it cannot be read. */
	private static int parseHex(String raw) {
		String text = raw.startsWith("#") ? raw.substring(1) : raw;
		try {
			return Integer.parseInt(text.trim().toLowerCase(Locale.ROOT), 16) & 0xFFFFFF;
		} catch (NumberFormatException e) {
			return 0xFFFFFF;
		}
	}

	/** The service dashes its uuids, but accept either spelling. */
	private static UUID parseUuid(String raw) {
		if (raw.isEmpty()) {
			return null;
		}
		try {
			if (raw.contains("-")) {
				return UUID.fromString(raw);
			}
			return UUID.fromString(raw.replaceFirst(
					"(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})"
							+ "(\\p{XDigit}{4})(\\p{XDigit}{12})",
					"$1-$2-$3-$4-$5"));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
