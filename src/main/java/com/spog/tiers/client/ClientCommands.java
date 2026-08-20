package com.spog.tiers.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.spog.tiers.SpogTiers;
import com.spog.tiers.client.gui.ProfileScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
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
			feedback(Component.literal("Usage: /tiers <player>").withStyle(ChatFormatting.RED));
			return true;
		}

		open(parts[1]);
		return true;
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

	/** Resolves a name to a profile via Mojang. Returns null when unknown. */
	private static GameProfile resolveProfile(String name) {
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
			return withTextures(new GameProfile(parseUndashed(id), resolved), id);
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
	private static GameProfile withTextures(GameProfile profile, String undashedId) {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(MOJANG_SESSION + undashedId))
					.header("Accept", "application/json")
					.header("User-Agent", "SpogTiers/1.0 (Minecraft mod)")
					.timeout(Duration.ofSeconds(10))
					.GET()
					.build();

			HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200 || response.body().isBlank()) {
				return profile;
			}

			JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
			JsonElement properties = root.get("properties");
			if (properties == null || !properties.isJsonArray()) {
				return profile;
			}

			JsonArray entries = properties.getAsJsonArray();
			for (JsonElement element : entries) {
				if (!element.isJsonObject()) {
					continue;
				}
				JsonObject property = element.getAsJsonObject();
				if (!"textures".equals(optString(property, "name"))) {
					continue;
				}
				String value = optString(property, "value");
				String signature = optString(property, "signature");
				profile.properties().put("textures", signature.isEmpty()
						? new Property("textures", value)
						: new Property("textures", value, signature));
				break;
			}
			return profile;
		} catch (Exception e) {
			SpogTiers.LOGGER.debug("Texture lookup failed for {}", profile.name(), e);
			return profile;
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

	private static void feedback(Component message) {
		Minecraft client = Minecraft.getInstance();
		if (client.gui != null) {
			client.gui.getChat().addClientSystemMessage(Component.literal("[SpogTiers] ")
					.withStyle(ChatFormatting.AQUA)
					.append(message));
		}
	}
}
