package com.spog.tiers.backend;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Name to UUID and back, against Mojang's public API.
 *
 * <p>Uses plain {@code java.net.http} rather than authlib: authlib would work,
 * but it is only published on {@code libraries.minecraft.net} and would add a
 * repository and a transitive tree for what is two GET requests.
 *
 * <p>Both directions are cached. Mojang rate-limits, and without a cache a
 * grader fat-fingering a name a few times, or a run of lookups for the same
 * player, would spend that budget for nothing.
 */
public final class MojangNames {
	private static final Logger LOG = LoggerFactory.getLogger(MojangNames.class);

	private static final String NAME_TO_ID =
			"https://api.mojang.com/users/profiles/minecraft/";
	private static final String ID_TO_NAME =
			"https://sessionserver.mojang.com/session/minecraft/profile/";

	/** How long a resolved answer is trusted. Renames are rare; ten minutes is ample. */
	private static final long TTL_MILLIS = 10 * 60 * 1000L;

	/** Thrown when Mojang could not be reached, as distinct from a player not existing. */
	public static final class Unavailable extends Exception {
		public Unavailable(Throwable cause) {
			super(cause);
		}
	}

	/** A cached answer and when it was taken. A null value is a cached "no such player". */
	private record Cached<T>(T value, long atMillis) {
		boolean fresh() {
			return System.currentTimeMillis() - atMillis < TTL_MILLIS;
		}
	}

	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();

	private final Map<String, Cached<UUID>> byName = new ConcurrentHashMap<>();
	private final Map<UUID, Cached<String>> byId = new ConcurrentHashMap<>();

	/**
	 * The UUID behind a name, or null if no such player exists.
	 *
	 * @throws Unavailable if Mojang could not be reached, so the caller can say
	 *     "try again" rather than the much worse "no such player"
	 */
	public UUID idFor(String name) throws Unavailable {
		if (name == null || name.isBlank()) {
			return null;
		}
		String key = name.trim().toLowerCase(Locale.ROOT);
		Cached<UUID> hit = byName.get(key);
		if (hit != null && hit.fresh()) {
			return hit.value();
		}

		String url = NAME_TO_ID + URLEncoder.encode(name.trim(), StandardCharsets.UTF_8);
		HttpResponse<String> response = get(url);
		// 204 is Mojang's historical "no such player"; 404 is the modern one.
		if (response.statusCode() == 204 || response.statusCode() == 404) {
			byName.put(key, new Cached<>(null, System.currentTimeMillis()));
			return null;
		}
		if (response.statusCode() != 200) {
			throw new Unavailable(new IllegalStateException(
					"mojang returned HTTP " + response.statusCode()));
		}
		try {
			JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
			UUID id = undashed(root.get("id").getAsString());
			String current = root.has("name") ? root.get("name").getAsString() : name.trim();
			byName.put(key, new Cached<>(id, System.currentTimeMillis()));
			byId.put(id, new Cached<>(current, System.currentTimeMillis()));
			return id;
		} catch (RuntimeException e) {
			throw new Unavailable(e);
		}
	}

	/**
	 * The current name for a UUID, or null if Mojang does not know it.
	 *
	 * <p>Best-effort: a failure here only costs a stale display name, so it
	 * returns null rather than throwing.
	 */
	public String nameFor(UUID id) {
		if (id == null) {
			return null;
		}
		Cached<String> hit = byId.get(id);
		if (hit != null && hit.fresh()) {
			return hit.value();
		}
		try {
			HttpResponse<String> response = get(ID_TO_NAME + id.toString().replace("-", ""));
			if (response.statusCode() != 200) {
				return hit == null ? null : hit.value();
			}
			JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
			String name = root.get("name").getAsString();
			byId.put(id, new Cached<>(name, System.currentTimeMillis()));
			return name;
		} catch (Unavailable | RuntimeException e) {
			LOG.debug("could not resolve a name for {} ({})", id, e.toString());
			return hit == null ? null : hit.value();
		}
	}

	private HttpResponse<String> get(String url) throws Unavailable {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.header("Accept", "application/json")
				.header("User-Agent", "DoorSMP-Backend/1.0")
				.timeout(Duration.ofSeconds(10))
				.GET()
				.build();
		try {
			return http.send(request, HttpResponse.BodyHandlers.ofString());
		} catch (java.io.IOException e) {
			throw new Unavailable(e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new Unavailable(e);
		}
	}

	/** Mojang returns UUIDs without dashes; {@link UUID#fromString} needs them. */
	public static UUID undashed(String raw) {
		String hex = raw.replace("-", "").trim();
		if (hex.length() != 32) {
			throw new IllegalArgumentException("not a uuid: " + raw);
		}
		return UUID.fromString(hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-"
				+ hex.substring(12, 16) + "-" + hex.substring(16, 20) + "-" + hex.substring(20));
	}
}
