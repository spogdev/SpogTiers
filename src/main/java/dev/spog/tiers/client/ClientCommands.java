package dev.spog.tiers.client;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.spog.tiers.SpogTiers;
import dev.spog.tiers.SpogTiersClient;
import dev.spog.tiers.client.gui.ProfileScreen;
import dev.spog.tiers.client.gui.TierlistScreen;
import dev.spog.tiers.data.DoorTierlist;
import net.minecraft.util.Formatting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientCommandSource;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.command.CommandSource;
import net.minecraft.text.Text;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Handles {@code /tiers <player>} entirely client-side.
 *
 * <p>The player does not need to be on the server: unknown names are resolved
 * through Mojang's profile API on a background thread, so any account can be
 * looked up.
 */
public final class ClientCommands {
	/**
	 * The command names, the first being the one documented everywhere.
	 *
	 * <p>The alias exists because "tiers" is a name other tier mods want too,
	 * and whichever registers last wins: the namespaced one is always ours.
	 */
	private static final List<String> PREFIXES = List.of("tiers", "spogtiers");

	/** The command that shows the whole Door SMP tierlist as a grid. */
	private static final String TIERLIST = "tierlist";
	private static final String MOJANG_PROFILE =
			"https://api.mojang.com/users/profiles/minecraft/";
	/** Session server: unlike the profile API, this returns skin textures. */
	private static final String MOJANG_SESSION =
			"https://sessionserver.mojang.com/session/minecraft/profile/";

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.build();

	private ClientCommands() {
	}

	/**
	 * Adds our commands to the client's dispatcher so they autocomplete and are
	 * not underlined as unknown while typing.
	 *
	 * <p>Execution still happens in {@link #handle(String)}: the dispatcher the
	 * client builds is only ever used for parsing and suggestions, so the node
	 * registered here carries suggestions and an inert executor.
	 */
	public static void register(CommandDispatcher<ClientCommandSource> dispatcher) {
		if (dispatcher == null) {
			return;
		}

		SuggestionProvider<ClientCommandSource> players = (context, builder) ->
				CommandSource.suggestMatching(
						context.getSource().getPlayerNames(), builder);

		for (String prefix : PREFIXES) {
			dispatcher.register(LiteralArgumentBuilder.<ClientCommandSource>literal(prefix)
					.executes(context -> 0)
					.then(RequiredArgumentBuilder
							.<ClientCommandSource, String>argument("player", StringArgumentType.word())
							.suggests(players)
							.executes(context -> 0)));
		}

		// Suggests only the players actually on our tierlist, rather than
		// everyone online: the command does nothing for anybody else, and a
		// suggestion that leads nowhere is worse than none.
		//
		// Asks for the roster as it suggests, so the names are there by the
		// time someone finishes typing. request() is cheap to call repeatedly.
		SuggestionProvider<ClientCommandSource> graded = (context, builder) -> {
			DoorTierlist.request();
			return CommandSource.suggestMatching(DoorTierlist.names(), builder);
		};

		dispatcher.register(LiteralArgumentBuilder.<ClientCommandSource>literal(TIERLIST)
				.executes(context -> 0)
				.then(RequiredArgumentBuilder
						.<ClientCommandSource, String>argument("player", StringArgumentType.word())
						.suggests(graded)
						.executes(context -> 0)));
	}

	/**
	 * @param command the command text without its leading slash
	 * @return true when handled client-side and the server must not see it
	 */
	public static boolean handle(String command) {
		String trimmed = command.trim();
		String lower = trimmed.toLowerCase(Locale.ROOT);
		if (lower.equals(TIERLIST) || lower.startsWith(TIERLIST + " ")) {
			String[] args = trimmed.split("\s+");
			// A named player is picked out in the grid rather than filtered to:
			// the point of the picture is where someone stands relative to
			// everyone else, which a single highlighted row still shows.
			openTierlist(args.length > 1 ? args[1] : null);
			return true;
		}

		boolean ours = false;
		for (String prefix : PREFIXES) {
			if (lower.equals(prefix) || lower.startsWith(prefix + " ")) {
				ours = true;
				break;
			}
		}
		if (!ours) {
			return false;
		}

		String[] parts = trimmed.split("\\s+");
		if (parts.length < 2) {
			// Bare /tiers shows your own tiers, which is the common case.
			openSelf();
			return true;
		}

		open(parts[1]);
		return true;
	}

	/**
	 * Opens the tierlist grid.
	 *
	 * <p>Deferred through {@code execute} for the same reason as the profile
	 * screen: this runs while the chat screen is still up, and chat closes
	 * itself afterwards.
	 */
	private static void openTierlist(String highlight) {
		MinecraftClient client = MinecraftClient.getInstance();
		DoorTierlist.request();
		client.execute(() ->
				client.setScreen(new TierlistScreen(client.currentScreen, highlight)));
	}

	/**
	 * Opens the local player's own profile.
	 *
	 * <p>Deferred through {@code execute}: this runs inside sendCommand while
	 * the chat screen is still up, and chat closes itself afterwards -- setting
	 * the screen here would be undone a moment later.
	 */
	private static void openSelf() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) {
			feedback(Text.literal("Not in a world").formatted(Formatting.RED));
			return;
		}
		GameProfile profile = client.player.getGameProfile();
		client.execute(() -> client.setScreen(new ProfileScreen(profile)));
	}

	private static void open(String name) {
		MinecraftClient client = MinecraftClient.getInstance();

		// Prefer the profile the server already gave us: it carries the skin
		// textures, so the model renders immediately with no extra request.
		GameProfile known = findOnline(client, name);
		if (known != null) {
			client.execute(() -> client.setScreen(new ProfileScreen(known)));
			return;
		}

		feedback(Text.literal("Looking up " + name + "...").formatted(Formatting.GRAY));
		CompletableFuture
				.supplyAsync(() -> {
					GameProfile resolved = resolveProfile(name);
					return resolved != null ? resolved : formerHolder(name);
				})
				.thenAcceptAsync(profile -> {
					if (profile == null) {
						feedback(Text.literal("No such player: " + name)
								.formatted(Formatting.RED));
						return;
					}
					client.setScreen(new ProfileScreen(profile));
				}, client);
	}

	/**
	 * The graded player who used to hold an unclaimed name, or null.
	 *
	 * <p>Only reached once Mojang has said no account answers to the name, so
	 * there is nobody for it to be confused with. The roster records the name
	 * each player was graded under, which is the one name we hold that Mojang
	 * may have let go.
	 *
	 * <p>Blocking, and called from the lookup worker rather than the render
	 * thread. Off unless asked for: see {@code oldNameSearching}.
	 */
	private static GameProfile formerHolder(String name) {
		if (!SpogTiersClient.config().oldNameSearching) {
			return null;
		}
		DoorTierlist.Player player = DoorTierlist.byName(name);
		if (player == null) {
			return null;
		}

		// The name the account answers to now. Asked for explicitly because
		// texturedProfile labels the profile with whatever name it is handed,
		// so passing the old one through would head the screen with a name
		// that no longer exists -- and make the notice below say that the
		// player is now themselves.
		String current = currentName(player.uuid());
		String label = current == null ? player.name() : current;

		// Textured, so the model is the player's own rather than a default
		// skin: a profile built from an id and a name alone carries none.
		GameProfile textured = texturedProfile(player.uuid(), label);
		GameProfile profile = textured != null
				? textured
				: new GameProfile(player.uuid(), label);

		// Says whose profile this is before it opens: it is headed by the new
		// name, so without this the answer to "/tiers oldname" is a
		// stranger's name with no reason given.
		if (current == null || current.equalsIgnoreCase(name)) {
			feedback(Text.literal("Showing " + label).formatted(Formatting.GRAY));
		} else {
			feedback(Text.literal(name + " is now ").formatted(Formatting.GRAY)
					.append(Text.literal(current).formatted(Formatting.WHITE)));
		}
		return profile;
	}

	/**
	 * The name an account answers to now, or null if it could not be read.
	 *
	 * <p>The session server is the only direction still open: Mojang removed
	 * the name-history endpoint, but id to current name still works, and that
	 * is what turns a recorded old name into the player it belonged to.
	 */
	private static String currentName(UUID id) {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(
							MOJANG_SESSION + id.toString().replace("-", "")))
					.header("Accept", "application/json")
					.header("User-Agent", "SpogTiers/1.0 (Minecraft mod)")
					.timeout(Duration.ofSeconds(10))
					.GET()
					.build();
			HttpResponse<String> response =
					HTTP.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200 || response.body().isBlank()) {
				return null;
			}
			JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
			String found = optString(root, "name");
			return found.isEmpty() ? null : found;
		} catch (Exception e) {
			SpogTiers.LOGGER.debug("Current-name lookup failed for {}", id, e);
			return null;
		}
	}

	/** Matches a name against the tab list, case-insensitively. */
	private static GameProfile findOnline(MinecraftClient client, String name) {
		if (client.getNetworkHandler() == null) {
			return null;
		}
		for (PlayerListEntry info : client.getNetworkHandler().getPlayerList()) {
			GameProfile profile = info.getProfile();
			if (profile.name().equalsIgnoreCase(name)) {
				return profile;
			}
		}
		return null;
	}

	/** Resolves a name to a profile via Mojang. Returns null when unknown. */
	/**
	 * Resolves a name to a profile via Mojang. Returns null when unknown.
	 *
	 * <p>Blocking, and shared with the tag editor's preview: this is the one
	 * place that knows how to turn a name into an id.
	 */
	public static GameProfile resolveProfile(String name) {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(MOJANG_PROFILE + name))
					.header("Accept", "application/json")
					.header("User-Agent", "SpogTiers/1.0 (MinecraftClient mod)")
					.timeout(Duration.ofSeconds(10))
					.GET()
					.build();

			HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
			// 204/404 both mean "no account with that name".
			if (response.statusCode() != 200 || response.body().isBlank()) {
				return null;
			}

			JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
			String id = root.get("id").getAsString();
			String resolved = root.has("name") ? root.get("name").getAsString() : name;
			return withTextures(parseUndashed(id), resolved, id);
		} catch (Exception e) {
			SpogTiers.LOGGER.debug("Profile lookup failed for {}", name, e);
			return null;
		}
	}

	/**
	 * Attaches the skin textures to a profile.
	 *
	 * <p>The profile API returns only a name and id. {@code SkinManager} reads
	 * skins from the profile's {@code textures} property, so without this the
	 * model falls back to the default Steve/Alex skin. The session server is
	 * what carries that property.
	 */
	/**
	 * The textured profile for a player we already know the id of.
	 *
	 * <p>Blocking, like {@link #resolveProfile}: callers run it off the render
	 * thread. The tierlist needs this because a profile built from an id and a
	 * name alone carries no textures property, and the skin provider reads
	 * skins from exactly that -- so such a profile always resolves to a default
	 * skin.
	 *
	 * <p>Strict, unlike {@link #withTextures}: null when the textures could not
	 * be read, rather than a bare profile. A caller drawing faces must be able
	 * to tell a failed fetch from a successful one, or it caches the failure
	 * and shows a default head that is not the player's skin.
	 */
	public static GameProfile texturedProfile(UUID id, String name) {
		GameProfile profile = withTextures(id, name, id.toString().replace("-", ""));
		// withTextures falls back to a bare profile on failure, which is right
		// for /tiers -- a dressed model is nice but the screen is still worth
		// opening. Here it is indistinguishable from success, so it is refused.
		return profile != null && profile.properties().containsKey("textures")
				? profile
				: null;
	}

	public static GameProfile withTextures(UUID id, String name, String undashedId) {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(MOJANG_SESSION + undashedId))
					.header("Accept", "application/json")
					.header("User-Agent", "SpogTiers/1.0 (MinecraftClient mod)")
					.timeout(Duration.ofSeconds(10))
					.GET()
					.build();

			HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200 || response.body().isBlank()) {
				return new GameProfile(id, name);
			}

			JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
			JsonElement properties = root.get("properties");
			if (properties == null || !properties.isJsonArray()) {
				return new GameProfile(id, name);
			}

			// PropertyMap copies its argument into an ImmutableMultimap, so it is
			// immutable however it is built -- populate a plain multimap first
			// and only then wrap it, or put() throws UnsupportedOperationException.
			Multimap<String, Property> collected = ArrayListMultimap.create();
			for (JsonElement element : properties.getAsJsonArray()) {
				if (!element.isJsonObject()) {
					continue;
				}
				JsonObject property = element.getAsJsonObject();
				String propertyName = optString(property, "name");
				if (propertyName.isEmpty()) {
					continue;
				}
				String value = optString(property, "value");
				String signature = optString(property, "signature");
				collected.put(propertyName, signature.isEmpty()
						? new Property(propertyName, value)
						: new Property(propertyName, value, signature));
			}
			return new GameProfile(id, name, new PropertyMap(collected));
		} catch (Exception e) {
			SpogTiers.LOGGER.warn("Texture lookup failed for {}", name, e);
			return new GameProfile(id, name);
		}
	}

	private static String optString(JsonObject object, String key) {
		JsonElement element = object.get(key);
		return element == null || element.isJsonNull() ? "" : element.getAsString();
	}

	/** Mojang returns UUIDs without dashes; {@link UUID#fromString} needs them. */
	private static UUID parseUndashed(String raw) {
		return UUID.fromString(raw.replaceFirst(
				"(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{12})",
				"$1-$2-$3-$4-$5"));
	}

	/**
	 * Status goes to the action bar rather than chat: these are transient
	 * notices about a screen that is about to open, not conversation worth
	 * keeping in the log.
	 */
	static void feedback(Text message) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.inGameHud != null) {
			client.inGameHud.setOverlayMessage(message, false);
		}
	}
}
