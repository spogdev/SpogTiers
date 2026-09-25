package com.spog.tiers.resolver;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.javalin.Javalin;
import io.javalin.http.Context;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * The Discord name resolver: a Minecraft UUID in, the linked Discord account
 * out.
 *
 * <pre>
 *   DISCORD_TOKEN=... java -jar spogtiers-resolver-all.jar --port 8082
 * </pre>
 *
 * <p>Its own service rather than a route on the Door SMP backend. The two share
 * nothing but a language: the tierlist is grades, graders and a bot people run
 * commands against, while this is a cache in front of two public APIs and one
 * Discord lookup. Separating them means the tierlist bot going down does not
 * take player names with it, a flood of name lookups cannot exhaust the
 * grading commands' rate limit, and either can be deployed without restarting
 * the other.
 *
 * <p><b>The token comes from the environment, never an argument.</b> Command
 * lines are world-readable in {@code ps}, so a token passed as {@code --token}
 * leaks to every user on the machine.
 *
 * <p>Put a TLS-terminating reverse proxy in front; see DEPLOY.md.
 */
public final class ResolverMain {
	private static final Logger LOG = LoggerFactory.getLogger(ResolverMain.class);

	/** Not 8081: that is the Door SMP backend, which runs alongside this. */
	private static final int DEFAULT_PORT = 8082;

	/** Where the token is kept when it is not in the environment. */
	private static final String TOKEN_FILE = "token.txt";

	/** Requests allowed per caller per window. */
	private static final int RATE_LIMIT = 120;
	private static final long RATE_WINDOW_MILLIS = 60_000L;

	/** How long a client may cache an answer. Handles change rarely. */
	private static final String CACHE_CONTROL = "public, max-age=1800";

	/**
	 * How long to wait on a lookup before giving up.
	 *
	 * <p>Up to three network hops sit behind it, and a caller drawing a
	 * profile would rather have a quick "not yet" than a held connection.
	 */
	private static final long TIMEOUT_SECONDS = 8;

	private static final Map<String, int[]> HITS = new ConcurrentHashMap<>();
	private static final Map<String, Long> WINDOW_START = new ConcurrentHashMap<>();

	private ResolverMain() {
	}

	public static void main(String[] args) throws Exception {
		int port = DEFAULT_PORT;
		String host = "0.0.0.0";
		Path dataDir = Path.of(".");

		for (int i = 0; i < args.length - 1; i++) {
			switch (args[i]) {
				case "--port" -> port = Integer.parseInt(args[++i]);
				case "--host" -> host = args[++i];
				case "--data-dir" -> dataDir = Path.of(args[++i]);
				default -> { }
			}
		}

		DiscordNames discord = new DiscordNames();

		// The API comes up first and is useful without the bot: a lookup asked
		// for before login, or with no token at all, still answers with the
		// linked id and leaves the name out.
		Javalin http = build(discord);
		http.start(host, port);
		LOG.info("Resolver listening on {}:{}", host, port);

		JDA jda = null;
		String token = resolveToken(dataDir);
		if (token == null || token.isBlank()) {
			LOG.warn("no Discord token found; ids will resolve but names will not. "
					+ "Set DISCORD_TOKEN or put the token in {}",
					dataDir.resolve(TOKEN_FILE).toAbsolutePath());
		} else {
			try {
				jda = login(token);
				discord.bot(jda);
				LOG.info("logged in as {}", jda.getSelfUser().getName());
			} catch (Exception e) {
				LOG.error("could not log in ({}); ids will resolve but names will not",
						e.toString());
			}
		}

		JDA bot = jda;
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			LOG.info("shutting down");
			// Each step guarded so one failure cannot skip the next, and a
			// throw here would only be logged as an unhandled error in a
			// shutdown thread -- alarming, about a process that is ending.
			try {
				if (bot != null) {
					bot.shutdown();
				}
			} catch (Throwable e) {
				LOG.debug("the Discord client did not shut down cleanly ({})", e.toString());
			}
			try {
				http.stop();
			} catch (Throwable e) {
				LOG.debug("the HTTP server did not shut down cleanly ({})", e.toString());
			}
		}));
	}

	/**
	 * Logs in with no intents and no caches.
	 *
	 * <p>Nothing here reacts to an event: the only Discord call is
	 * {@code retrieveUserById}, which is REST rather than gateway, so none of
	 * the caches would ever be read and no privileged intent is needed.
	 */
	private static JDA login(String token) throws InterruptedException {
		JDA jda = JDABuilder.createDefault(token)
				.enableIntents(Collections.emptyList())
				.setMemberCachePolicy(net.dv8tion.jda.api.utils.MemberCachePolicy.NONE)
				.disableCache(EnumSet.allOf(net.dv8tion.jda.api.utils.cache.CacheFlag.class))
				.build();
		jda.awaitReady();
		return jda;
	}

	/** Builds the server. The caller starts it, so startup order stays in one place. */
	static Javalin build(DiscordNames discord) {
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
				"discord", discord.stats())));

		app.get("/api/v1/discord/{uuid}", ctx -> lookup(ctx, discord));

		app.exception(Exception.class, (e, ctx) -> {
			LOG.error("unhandled error serving {}", ctx.path(), e);
			ctx.status(500).json(Map.of("error", "internal error"));
		});

		return app;
	}

	/**
	 * The Discord account linked to a player, by UUID.
	 *
	 * <p>Answers 404 for a player with nothing linked: the mod reads 404 as
	 * "nothing here" rather than as a failure, and most players have linked
	 * nothing.
	 */
	private static void lookup(Context ctx, DiscordNames discord) {
		UUID id;
		try {
			id = uuid(ctx.pathParam("uuid"));
		} catch (IllegalArgumentException e) {
			ctx.status(400).json(Map.of("error", "malformed uuid"));
			return;
		}
		ctx.future(() -> discord.lookup(id)
				.completeOnTimeout(null, TIMEOUT_SECONDS, TimeUnit.SECONDS)
				.thenAccept(account -> {
					if (account == null) {
						ctx.status(404)
								.header("Cache-Control", CACHE_CONTROL)
								.json(Map.of("error", "no linked discord"));
						return;
					}
					JsonObject out = new JsonObject();
					out.addProperty("uuid", id.toString());
					out.addProperty("id", account.id());
					// Both may be absent: the id resolves from the tierlists
					// alone, while naming it needs the bot.
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
					ctx.header("Cache-Control", CACHE_CONTROL);
					ctx.contentType("application/json").result(out.toString());
				}));
	}

	/** Dashed or undashed, both accepted. */
	static UUID uuid(String raw) {
		String hex = raw == null ? "" : raw.replace("-", "").trim();
		if (hex.length() != 32) {
			throw new IllegalArgumentException("not a uuid: " + raw);
		}
		return UUID.fromString(hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-"
				+ hex.substring(12, 16) + "-" + hex.substring(16, 20) + "-" + hex.substring(20));
	}

	/**
	 * The caller's own address, seen past the proxy.
	 *
	 * <p>A reverse proxy terminates TLS and forwards, so {@code ctx.ip()} is
	 * the proxy's address for every request -- which would put every user in
	 * one shared bucket and refuse them all at once.
	 *
	 * <p>The header is trusted only because the service binds to localhost
	 * behind that proxy, so a client cannot set it itself and be believed.
	 */
	static String clientIp(Context ctx) {
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
	 * A fixed-window limiter, per caller.
	 *
	 * <p>Not a precise token bucket -- a caller can get up to twice the limit
	 * across a window boundary -- but this exists to stop a flood from
	 * exhausting a small VPS, not to meter usage.
	 */
	static boolean allow(String ip) {
		long now = System.currentTimeMillis();
		Long started = WINDOW_START.get(ip);
		if (started == null || now - started > RATE_WINDOW_MILLIS) {
			WINDOW_START.put(ip, now);
			HITS.put(ip, new int[] { 1 });
			// The map only grows with distinct client IPs; clear it out
			// whenever it gets large rather than tracking expiry per entry.
			if (HITS.size() > 10_000) {
				HITS.clear();
				WINDOW_START.clear();
			}
			return true;
		}
		int[] count = HITS.computeIfAbsent(ip, k -> new int[] { 0 });
		synchronized (count) {
			return ++count[0] <= RATE_LIMIT;
		}
	}

	/**
	 * The bot token, from the environment or from a file beside the data.
	 *
	 * <p>The environment wins, so a systemd unit's {@code EnvironmentFile}
	 * stays the way a server runs this. The file is for running it by hand,
	 * where exporting a variable every time is a nuisance and it is easy to
	 * end up pasting the token onto a command line instead -- which leaks it
	 * to every user on the machine through {@code ps}.
	 */
	private static String resolveToken(Path dataDir) {
		String fromEnv = System.getenv("DISCORD_TOKEN");
		if (fromEnv != null && !fromEnv.isBlank()) {
			return fromEnv.trim();
		}
		Path file = dataDir.resolve(TOKEN_FILE);
		if (!Files.exists(file)) {
			return null;
		}
		try {
			// Only the first non-blank line, so a trailing newline or a
			// comment underneath does not become part of the token.
			for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
				String trimmed = line.trim();
				if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
					LOG.info("read the Discord token from {}", file.getFileName());
					return trimmed;
				}
			}
			LOG.warn("{} is empty", file);
		} catch (IOException e) {
			LOG.error("could not read {} ({})", file, e.toString());
		}
		return null;
	}
}
