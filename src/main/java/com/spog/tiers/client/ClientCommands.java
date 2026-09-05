package com.spog.tiers.client;

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
import com.spog.tiers.SpogTiers;
import com.spog.tiers.client.gui.ProfileScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
	private static final String PREFIX = "tiers";
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
	public static void register(CommandDispatcher<ClientSuggestionProvider> dispatcher) {
		if (dispatcher == null) {
			return;
		}

		SuggestionProvider<ClientSuggestionProvider> players = (context, builder) ->
				SharedSuggestionProvider.suggest(
						context.getSource().getOnlinePlayerNames(), builder);

		LiteralArgumentBuilder<ClientSuggestionProvider> root =
				LiteralArgumentBuilder.<ClientSuggestionProvider>literal(PREFIX)
						.executes(context -> 0)
						.then(RequiredArgumentBuilder
								.<ClientSuggestionProvider, String>argument("player", StringArgumentType.word())
								.suggests(players)
								.executes(context -> 0));

		dispatcher.register(root);
	}

	/**
	 * @param command the command text without its leading slash
	 * @return true when handled client-side and the server must not see it
	 */
	public static boolean handle(String command) {
		String trimmed = command.trim();
		String lower = trimmed.toLowerCase(Locale.ROOT);
		if (!lower.equals(PREFIX) && !lower.startsWith(PREFIX + " ")) {
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
	 * Opens the local player's own profile.
	 *
	 * <p>Deferred through {@code execute}: this runs inside sendCommand while
	 * the chat screen is still up, and chat closes itself afterwards -- setting
	 * the screen here would be undone a moment later.
	 */
	private static void openSelf() {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) {
			feedback(Component.literal("Not in a world").withStyle(ChatFormatting.RED));
			return;
		}
		GameProfile profile = client.player.getGameProfile();
		client.execute(() -> client.setScreen(new ProfileScreen(profile)));
	}

	private static void open(String name) {
		Minecraft client = Minecraft.getInstance();

		// Prefer the profile the server already gave us: it carries the skin
		// textures, so the model renders immediately with no extra request.
		GameProfile known = findOnline(client, name);
		if (known != null) {
			client.execute(() -> client.setScreen(new ProfileScreen(known)));
			return;
		}

		feedback(Component.literal("Looking up " + name + "...").withStyle(ChatFormatting.GRAY));
		CompletableFuture
				.supplyAsync(() -> resolveProfile(name))
				.thenAcceptAsync(profile -> {
					if (profile == null) {
						feedback(Component.literal("No such player: " + name)
								.withStyle(ChatFormatting.RED));
						return;
					}
					client.setScreen(new ProfileScreen(profile));
				}, client);
	}

	/** Matches a name against the tab list, case-insensitively. */
	private static GameProfile findOnline(Minecraft client, String name) {
		if (client.getConnection() == null) {
			return null;
		}
		for (PlayerInfo info : client.getConnection().getOnlinePlayers()) {
			GameProfile profile = info.getProfile();
			if (profile.name().equalsIgnoreCase(name)) {
				return profile;
			}
		}
		return null;
	}

	/**
	 * Resolves a name to a profile via Mojang. Returns null when unknown.
	 *
	 * <p>Blocking, and shared with the tag editor's preview: this is the one
	 * place that knows how to turn a name into an id, and duplicating it would
	 * mean two lookups that could disagree.
	 */
	public static GameProfile resolveProfile(String name) {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(MOJANG_PROFILE + name))
					.header("Accept", "application/json")
					.header("User-Agent", "SpogTiers/1.0 (Minecraft mod)")
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
	private static GameProfile withTextures(UUID id, String name, String undashedId) {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(MOJANG_SESSION + undashedId))
					.header("Accept", "application/json")
					.header("User-Agent", "SpogTiers/1.0 (Minecraft mod)")
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
	static void feedback(Component message) {
		Minecraft client = Minecraft.getInstance();
		if (client.gui != null) {
			client.gui.setOverlayMessage(message, false);
		}
	}
}
