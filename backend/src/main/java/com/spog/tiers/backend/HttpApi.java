package com.spog.tiers.backend;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * The read-only HTTP API the mod talks to.
 *
 * <p><b>There is no write route.</b> Grades are set from Discord, in this same
 * process, so exposing a write endpoint would add an attack surface that buys
 * nothing. Reads need no authentication either: grades are public, and the mod
 * ships no credentials it could keep secret anyway.
 *
 * <p>The 404-for-ungraded convention is deliberate and load-bearing. The mod
 * already treats 404 from a tierlist as the normal "not on this list" answer
 * rather than an error, so answering that way needs no special handling there.
 */
public final class HttpApi {
	private static final Logger LOG = LoggerFactory.getLogger(HttpApi.class);

	/** Requests allowed per IP per window. Generous for a client, useless for a flood. */
	private static final int RATE_LIMIT = 60;
	private static final long RATE_WINDOW_MILLIS = 60_000L;

	/** How long a client may cache a grade. Grades change rarely. */
	private static final String CACHE_CONTROL = "public, max-age=300";

	/** How long a client may cache a Discord answer. Handles change rarely. */
	private static final String DISCORD_CACHE_CONTROL = "public, max-age=1800";

	/**
	 * How long the server will wait on a Discord lookup before giving up.
	 *
	 * <p>Two network hops sit behind it, and a caller drawing a nameplate
	 * would rather have a quick "not yet" than a held connection.
	 */
	private static final long DISCORD_TIMEOUT_SECONDS = 8;

	private final GradeStore grades;
	private final MojangNames names;
	private final DiscordNames discord;
	private final Map<String, int[]> hits = new ConcurrentHashMap<>();
	private final Map<String, Long> windowStart = new ConcurrentHashMap<>();

	public HttpApi(GradeStore grades, MojangNames names, DiscordNames discord) {
		this.grades = grades;
		this.names = names;
		this.discord = discord;
	}

	/** Build the server. The caller starts it, so startup order stays in one place. */
	public Javalin build() {
		Javalin app = Javalin.create(config -> {
			config.showJavalinBanner = false;
		});

		app.before(ctx -> {
			if (!allow(clientIp(ctx))) {
				ctx.status(429).json(Map.of("error", "rate limited"));
				ctx.skipRemainingHandlers();
			}
		});

		app.get("/health", ctx -> ctx.json(Map.of(
				"status", "ok",
				"grades", grades.size(),
				"discord", discord.stats())));

		app.get("/api/v1/grade/{uuid}", this::byUuid);
		app.get("/api/v1/grade/name/{name}", this::byName);
		app.get("/api/v1/discord/{uuid}", this::discordByUuid);

		app.exception(Exception.class, (e, ctx) -> {
			LOG.error("unhandled error serving {}", ctx.path(), e);
			ctx.status(500).json(Map.of("error", "internal error"));
		});

		return app;
	}

	private void byUuid(Context ctx) {
		UUID id;
		try {
			id = MojangNames.undashed(ctx.pathParam("uuid"));
		} catch (IllegalArgumentException e) {
			ctx.status(400).json(Map.of("error", "malformed uuid"));
			return;
		}
		respond(ctx, id);
	}

	private void byName(Context ctx) {
		UUID id;
		try {
			id = names.idFor(ctx.pathParam("name"));
		} catch (MojangNames.Unavailable e) {
			// Not the client's fault and not "no such player" -- say so, so a
			// caller can retry rather than concluding the player does not exist.
			ctx.status(502).json(Map.of("error", "could not reach mojang"));
			return;
		}
		if (id == null) {
			ctx.status(404).json(Map.of("error", "no such player"));
			return;
		}
		respond(ctx, id);
	}

	/**
	 * The Discord account linked to a player, by UUID.
	 *
	 * <p>Answers 404 for a player with nothing linked, matching the grade
	 * routes: the mod already reads 404 from us as "nothing here" rather than
	 * as a failure, and most players have linked nothing.
	 *
	 * <p>Handled with Javalin's future support so the request thread is
	 * released while the two hops behind it run.
	 */
	private void discordByUuid(Context ctx) {
		UUID id;
		try {
			id = MojangNames.undashed(ctx.pathParam("uuid"));
		} catch (IllegalArgumentException e) {
			ctx.status(400).json(Map.of("error", "malformed uuid"));
			return;
		}
		ctx.future(() -> discord.lookup(id)
				.completeOnTimeout(null, DISCORD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
				.thenAccept(account -> {
					if (account == null) {
						ctx.status(404)
								.header("Cache-Control", DISCORD_CACHE_CONTROL)
								.json(Map.of("error", "no linked discord"));
						return;
					}
					JsonObject out = new JsonObject();
					out.addProperty("uuid", id.toString());
					out.addProperty("id", account.id());
					// Both may be absent: the id resolves from the tierlists
					// alone, while naming it needs the bot, which the server
					// runs without when no token was given.
					out.addProperty("username", account.username());
					out.addProperty("displayName", account.displayName());
					// The accounts the two lists disagreed about, when they
					// did. Normally empty, and left out entirely then rather
					// than sent as an empty array a client has to check.
					if (!account.others().isEmpty()) {
						JsonArray others = new JsonArray();
						for (DiscordNames.Account other : account.others()) {
							JsonObject entry = new JsonObject();
							entry.addProperty("id", other.id());
							entry.addProperty("username", other.username());
							entry.addProperty("displayName", other.displayName());
							others.add(entry);
						}
						out.add("others", others);
					}
					ctx.header("Cache-Control", DISCORD_CACHE_CONTROL);
					ctx.contentType("application/json").result(out.toString());
				}));
	}

	private void respond(Context ctx, UUID id) {
		GradeStore.Record record = grades.get(id);
		if (record == null) {
			// The overwhelmingly common case: most players are not graded.
			ctx.status(404)
					.header("Cache-Control", CACHE_CONTROL)
					.json(Map.of("error", "not graded"));
			return;
		}

		JsonObject out = new JsonObject();
		out.addProperty("uuid", record.uuid().toString());
		out.addProperty("name", record.name());
		out.addProperty("grade", record.grade().label());
		out.addProperty("color", record.grade().hex());
		out.addProperty("gradedAt", record.gradedAt());
		// Retirement is published: the mod draws a retired tier as R<tier>.
		// Display order is not -- that is the tierlist picture's business, and
		// a single badge has no notion of who stands beside it.
		out.addProperty("retired", record.retired());

		ctx.header("Cache-Control", CACHE_CONTROL);
		ctx.contentType("application/json").result(out.toString());
	}

	/**
	 * The caller's own address, seen past the proxy.
	 *
	 * <p>Caddy terminates TLS and forwards, so {@code ctx.ip()} is Caddy's
	 * own address for every request -- which put every user of the mod in one
	 * shared bucket and returned 429 to all of them once any handful of
	 * players had been looked up. That is the "everything works, then 429s"
	 * case the deploy notes warned about.
	 *
	 * <p>The first entry in X-Forwarded-For is the original client; the rest
	 * are proxies it passed through. Trusted only because nothing reaches
	 * this port but our own proxy -- the service binds 127.0.0.1, so a client
	 * cannot set the header itself and be believed.
	 */
	private static String clientIp(Context ctx) {
		String forwarded = ctx.header("X-Forwarded-For");
		if (forwarded != null && !forwarded.isBlank()) {
			int comma = forwarded.indexOf(',');
			String first = (comma < 0 ? forwarded : forwarded.substring(0, comma)).trim();
			if (!first.isEmpty()) {
				return first;
			}
		}
		return ctx.ip();
	}

	/**
	 * A fixed-window limiter, per IP.
	 *
	 * <p>Not a precise token bucket -- a caller can get up to twice the limit
	 * across a window boundary -- but this exists to stop a flood from
	 * exhausting a small VPS, not to meter usage, and a fixed window does that
	 * with one map lookup and no sweeper thread.
	 */
	private boolean allow(String ip) {
		long now = System.currentTimeMillis();
		Long started = windowStart.get(ip);
		if (started == null || now - started > RATE_WINDOW_MILLIS) {
			windowStart.put(ip, now);
			hits.put(ip, new int[] { 1 });
			// The map only grows with distinct client IPs; clear it out whenever
			// it gets large rather than tracking expiry per entry.
			if (hits.size() > 10_000) {
				hits.clear();
				windowStart.clear();
			}
			return true;
		}
		int[] count = hits.computeIfAbsent(ip, k -> new int[] { 0 });
		synchronized (count) {
			return ++count[0] <= RATE_LIMIT;
		}
	}
}
