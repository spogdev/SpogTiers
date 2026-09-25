package com.spog.tiers.resolver;

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
import java.util.ArrayList;
import java.util.List;
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
 * <p><b>The second hop is why this lives on a server.</b> Resolving a
 * snowflake needs a bot token, and a token shipped inside the mod would be
 * extractable by anyone holding the jar and revoked by Discord soon after. The
 * mod asks this service instead and never sees a credential.
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

	/** The two tierlists that publish a linked Discord id. */
	private static final String SUBTIERS_PROFILE = "https://subtiers.net/api/profile/";
	private static final String MCTIERS_PROFILE = "https://mctiers.com/api/v2/profile/";

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

	/**
	 * What we know about one player's Discord account, if anything.
	 *
	 * <p>{@code others} carries the accounts a second list linked that the
	 * first did not agree with. It is normally empty: the two lists agree for
	 * most players, and disagreement means the player linked different
	 * accounts to each, which is a real thing that happens and not an error
	 * either side can be blamed for.
	 */
	public record Account(String id, String username, String displayName,
			List<Account> others) {
		public Account(String id, String username, String displayName) {
			this(id, username, displayName, List.of());
		}
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
	 * <p>Both lists are asked, concurrently. Where they agree -- which is the
	 * usual case -- the answer is that one account. Where they disagree the
	 * player has linked a different account to each, and both are returned
	 * rather than one being picked: choosing between them would mean deciding
	 * which list is more current, which neither publishes and we cannot know.
	 *
	 * <p>Asynchronous to the end: every hop is a network round trip, and
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
		// Side by side rather than one after the other: they are independent,
		// and asking in sequence would double the wait for no benefit.
		// Each list fails on its own. Letting one failure through would lose
		// the other list's answer as well, which turns "one site is down"
		// into "this player has linked nothing" -- and that would then be
		// cached as a miss.
		CompletableFuture<String> fromSub = source(SUBTIERS_PROFILE, uuid);
		CompletableFuture<String> fromMc = source(MCTIERS_PROFILE, uuid);
		return fromSub.thenCombine(fromMc, DiscordNames::distinct)
				.thenCompose(this::resolveAll)
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

	/** One list's answer, with its own failure swallowed to null. */
	private CompletableFuture<String> source(String endpoint, UUID uuid) {
		return CompletableFuture
				.supplyAsync(() -> linkedId(endpoint, uuid))
				.exceptionally(error -> {
					LOG.debug("{} would not answer for {} ({})",
							endpoint, uuid, error.toString());
					return null;
				});
	}

	/**
	 * The distinct ids two lists reported, in a stable order.
	 *
	 * <p>Nulls drop out, and an id both lists gave appears once -- agreement
	 * is the common case and has to collapse to a single account rather than
	 * being reported as two identical ones.
	 */
	private static List<String> distinct(String first, String second) {
		List<String> ids = new ArrayList<>(2);
		for (String id : new String[] {first, second}) {
			if (id != null && !ids.contains(id)) {
				ids.add(id);
			}
		}
		return ids;
	}

	/**
	 * Every id resolved, folded into one account carrying the rest.
	 *
	 * <p>The first is the answer and any others hang off it, so a caller that
	 * only wants a name can ignore the difference entirely.
	 */
	private CompletableFuture<Account> resolveAll(List<String> ids) {
		if (ids.isEmpty()) {
			return CompletableFuture.completedFuture(null);
		}
		List<CompletableFuture<Account>> pending = new ArrayList<>(ids.size());
		for (String id : ids) {
			pending.add(resolve(id));
		}
		return CompletableFuture
				.allOf(pending.toArray(new CompletableFuture[0]))
				.thenApply(ignored -> {
					List<Account> found = new ArrayList<>(pending.size());
					for (CompletableFuture<Account> one : pending) {
						Account account = one.join();
						if (account != null) {
							found.add(account);
						}
					}
					if (found.isEmpty()) {
						return null;
					}
					Account first = found.get(0);
					return new Account(first.id(), first.username(), first.displayName(),
							List.copyOf(found.subList(1, found.size())));
				});
	}

	/** The snowflake one list has on file, or null if it has none. */
	private String linkedId(String profileEndpoint, UUID uuid) {
		String url = profileEndpoint + uuid.toString().replace("-", "");
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.header("Accept", "application/json")
				.header("User-Agent", "SpogTiers-Resolver/1.0")
				.timeout(Duration.ofSeconds(10))
				.GET()
				.build();
		try {
			HttpResponse<String> response =
					http.send(request, HttpResponse.BodyHandlers.ofString());
			// 404 is a list's answer for a player it has never seen, which is
			// most of them, and is not a failure.
			if (response.statusCode() == 404) {
				return null;
			}
			if (response.statusCode() != 200) {
				throw new IllegalStateException(
						profileEndpoint + " returned HTTP " + response.statusCode());
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
	 * <p>Discord has migrated to unique handles with no discriminator. A
	 * migrated account reports a placeholder rather than nothing, and it is
	 * not always the same one -- both "0" and "0000" appear -- so the test is
	 * whether the digits mean anything rather than a match against one
	 * spelling. Checking only "0" is what put "#0000" after every name.
	 *
	 * <p>An account that never migrated still carries a real discriminator,
	 * and its handle does not identify anyone without it, so that case keeps
	 * the suffix.
	 */
	private static String handle(User user) {
		String discriminator = user.getDiscriminator();
		boolean legacy = discriminator != null
				&& discriminator.chars().anyMatch(digit -> digit != '0');
		return legacy ? user.getName() + "#" + discriminator : user.getName();
	}

	/** For the health endpoint, so an operator can see the cache doing its job. */
	public Map<String, Object> stats() {
		return Map.of("cached", cache.size(), "bot", available());
	}
}
