package com.spog.tiers.backend;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A Minecraft UUID to the Discord account behind it, in two hops.
 *
 * <p>SubTiers publishes a {@code discord_id} on the profiles of players who
 * have linked their account -- it is the only one of the four tierlists that
 * does. That is a snowflake, not a name, so the second hop asks Discord for
 * the account it belongs to.
 *
 * <p><b>The second hop is why this lives on the server.</b> Resolving a
 * snowflake needs a bot token, and a token shipped inside the mod would be
 * extractable by anyone holding the jar and revoked by Discord soon after. The
 * bot is already running in this process, so the mod can ask us instead and
 * never sees a credential.
 *
 * <p>{@code retrieveUserById} is a REST call rather than a gateway lookup, so
 * it works with the bot's caches disabled and no privileged intents, and for
 * any account rather than only members of servers the bot is in.
 *
 * <p>Everything is cached and every failure is soft. A name that cannot be
 * resolved is a missing line in a UI, not an error worth failing a request
 * over.
 */
public final class DiscordNames {
	private static final Logger LOG = LoggerFactory.getLogger(DiscordNames.class);

	/** The only tierlist that publishes a linked Discord id. */
	private static final String SUBTIERS_PROFILE = "https://subtiers.net/api/profile/";

	/**
	 * How long an answer is trusted.
	 *
	 * <p>Longer than the Mojang cache: a Discord handle changes far less often
	 * than a request for one arrives, and every miss costs a call against a
	 * rate limit shared by the whole bot -- including the grading commands,
	 * which matter more than a name in a tooltip.
	 */
	private static final long TTL_MILLIS = 60 * 60 * 1000L;

	/** How long a "nothing linked" answer is trusted, before asking again. */
	private static final long MISS_TTL_MILLIS = 15 * 60 * 1000L;

	/** What we know about one player's Discord account, if anything. */
	public record Account(String id, String username, String displayName) {
	}

	private record Cached(Account value, long atMillis) {
		boolean fresh() {
			long ttl = value == null ? MISS_TTL_MILLIS : TTL_MILLIS;
			return System.currentTimeMillis() - atMillis < ttl;
		}
	}

	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();

	private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();

	/**
	 * The bot, once it has logged in.
	 *
	 * <p>Volatile and set after construction because the API starts before the
	 * bot does, and outlives a failed login: the server is useful without it,
	 * and a null here only costs the name half of the answer.
	 */
	private volatile JDA jda;

	/** Hands over the bot once it is ready, or null if it never started. */
	public void bot(JDA bot) {
		this.jda = bot;
	}

	/** Whether names can be resolved at all right now. */
	public boolean available() {
		return jda != null;
	}

	/**
	 * The Discord account linked to a player, or null when there is none.
	 *
	 * <p>Asynchronous to the end: both hops are network round trips, and
	 * blocking on them would tie up a request thread for their duration.
	 */
	public CompletableFuture<Account> lookup(UUID uuid) {
		if (uuid == null) {
			return CompletableFuture.completedFuture(null);
		}
		Cached hit = cache.get(uuid);
		if (hit != null && hit.fresh()) {
			return CompletableFuture.completedFuture(hit.value());
		}
		return CompletableFuture
				.supplyAsync(() -> linkedId(uuid))
				.thenCompose(id -> id == null
						? CompletableFuture.completedFuture((Account) null)
						: resolve(id))
				.handle((account, error) -> {
					if (error != null) {
						LOG.debug("could not resolve a Discord account for {} ({})",
								uuid, error.toString());
						// A stale answer beats none, and a failure is not
						// evidence that nothing is linked -- so a miss is only
						// cached when the lookup actually succeeded.
						return hit == null ? null : hit.value();
					}
					cache.put(uuid, new Cached(account, System.currentTimeMillis()));
					return account;
				});
	}

	/** The snowflake SubTiers has on file, or null if it has none. */
	private String linkedId(UUID uuid) {
		String url = SUBTIERS_PROFILE + uuid.toString().replace("-", "");
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.header("Accept", "application/json")
				.header("User-Agent", "DoorSMP-Backend/1.0")
				.timeout(Duration.ofSeconds(10))
				.GET()
				.build();
		try {
			HttpResponse<String> response =
					http.send(request, HttpResponse.BodyHandlers.ofString());
			// 404 is SubTiers' answer for a player it has never seen, which is
			// most of them, and is not a failure.
			if (response.statusCode() == 404) {
				return null;
			}
			if (response.statusCode() != 200) {
				throw new IllegalStateException(
						"subtiers returned HTTP " + response.statusCode());
			}
			JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
			if (!root.has("discord_id") || root.get("discord_id").isJsonNull()) {
				return null;
			}
			String id = root.get("discord_id").getAsString().trim();
			// Guarded rather than trusted: this goes into a Discord API path,
			// and a snowflake is digits and nothing else.
			return id.matches("\\d{1,20}") ? id : null;
		} catch (java.io.IOException e) {
			throw new java.io.UncheckedIOException(e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

	/** The account a snowflake belongs to, asked of Discord. */
	private CompletableFuture<Account> resolve(String id) {
		JDA bot = jda;
		if (bot == null) {
			// The id is worth returning on its own: a caller can still say an
			// account is linked even when we cannot name it.
			return CompletableFuture.completedFuture(new Account(id, null, null));
		}
		// submit() rather than complete(): complete() blocks the calling thread
		// until Discord answers, which on a request thread means one slow
		// lookup holds a server thread for the whole round trip.
		return bot.retrieveUserById(id).submit()
				.thenApply(user -> new Account(id, handle(user), user.getEffectiveName()))
				.exceptionally(error -> {
					// A deleted account, or a snowflake that was never one.
					// Still worth saying an id is on file.
					LOG.debug("Discord would not resolve {} ({})", id, error.toString());
					return new Account(id, null, null);
				});
	}

	/**
	 * A user's handle, in whichever scheme their account uses.
	 *
	 * <p>Discord has migrated to unique handles with no discriminator, but
	 * accounts that never migrated still carry one, and JDA reports "0" for
	 * those that did.
	 */
	private static String handle(User user) {
		String discriminator = user.getDiscriminator();
		return discriminator == null || discriminator.equals("0")
				? user.getName()
				: user.getName() + "#" + discriminator;
	}

	/** For the health endpoint, so an operator can see the cache doing its job. */
	public Map<String, Object> stats() {
		return Map.of("cached", cache.size(), "bot", available());
	}
}
