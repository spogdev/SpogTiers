package dev.spog.tiers.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import dev.spog.spore.ui.theme.SporeTheme;
import dev.spog.spore.ui.toast.SporeToast;
import dev.spog.tiers.SpogTiers;
import dev.spog.tiers.SpogTiersClient;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Says when a newer SpogTiers is out, once a launch, the first time the
 * multiplayer menu opens.
 *
 * <p>Asks Modrinth for the versions built for this Minecraft version and for
 * Fabric, and compares the newest with the one running. Only those builds
 * count, so a release for another Minecraft version never claims an update
 * this player cannot install. Any failure -- offline, Modrinth down, a version
 * it cannot read -- shows nothing.
 *
 * <p>The same check Relay makes, shown as a SporeUI toast.
 */
public final class UpdateChecker {
	private static final String PROJECT = "spogtiers";
	private static final Gson GSON = new Gson();
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();
	/** One update toast at a time. */
	private static final Object TOAST = new Object();

	/** Set once the check has run this launch; it only ever runs once. */
	private static boolean checked;

	private UpdateChecker() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof JoinMultiplayerScreen && !checked) {
				checked = true;
				check();
			}
		});
	}

	private static void check() {
		String current = FabricLoader.getInstance().getModContainer(SpogTiers.MOD_ID)
				.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse(null);
		if (current == null || !SpogTiersClient.allowed()
				|| !SpogTiersClient.config().updateToast) {
			return;
		}
		String gameVersion = SharedConstants.getCurrentVersion().name();
		String query = "?loaders=" + encode("[\"fabric\"]")
				+ "&game_versions=" + encode("[\"" + gameVersion + "\"]");
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("https://api.modrinth.com/v2/project/" + PROJECT + "/version" + query))
				.header("User-Agent", "spogdev/spogtiers/" + current)
				.timeout(Duration.ofSeconds(10))
				.GET()
				.build();
		HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenAccept(response -> {
			if (response.statusCode() != 200) {
				return;
			}
			String latest = newest(GSON.fromJson(response.body(), JsonArray.class));
			if (latest != null && compare(release(latest), release(current)) > 0) {
				show(release(latest), release(current));
			}
		}).exceptionally(e -> {
			SpogTiers.LOGGER.debug("Update check failed: {}", e.toString());
			return null;
		});
	}

	/** Toasts must be added on the game thread; the answer arrives on the HTTP client's. */
	private static void show(String latest, String current) {
		Minecraft.getInstance().execute(() -> SporeToast.show(new SporeToast(TOAST,
				Component.literal("SpogTiers " + latest + " is available"),
				Component.literal("You have " + current + ". Get the update on Modrinth"),
				SporeToast.DEFAULT_DISPLAY_MS, SporeTheme.ACCENT)));
	}

	/** The highest version number among Modrinth's answers, or null if there are none. */
	static String newest(JsonArray versions) {
		String best = null;
		for (JsonElement el : versions) {
			String number = el.getAsJsonObject().get("version_number").getAsString();
			if (best == null || compare(release(number), release(best)) > 0) {
				best = number;
			}
		}
		return best;
	}

	/** {@code 0.3.4+26.1.2} as {@code 0.3.4}: the Minecraft version after the {@code +} is not ours. */
	static String release(String version) {
		int plus = version.indexOf('+');
		return plus < 0 ? version : version.substring(0, plus);
	}

	/**
	 * Compares two dotted version numbers part by part, numerically, so 0.10.0
	 * is newer than 0.9.0. A pre-release suffix ({@code 0.4.0-beta}) sorts
	 * before the release it leads up to.
	 */
	static int compare(String a, String b) {
		String[] as = a.split("-", 2);
		String[] bs = b.split("-", 2);
		String[] ap = as[0].split("\\.");
		String[] bp = bs[0].split("\\.");
		for (int i = 0; i < Math.max(ap.length, bp.length); i++) {
			int x = i < ap.length ? number(ap[i]) : 0;
			int y = i < bp.length ? number(bp[i]) : 0;
			if (x != y) {
				return Integer.compare(x, y);
			}
		}
		// Same numbers: a release beats its own pre-release.
		if (as.length != bs.length) {
			return as.length < bs.length ? 1 : -1;
		}
		return as.length == 2 ? as[1].compareTo(bs[1]) : 0;
	}

	private static int number(String part) {
		try {
			return Integer.parseInt(part.replaceAll("\\D.*", ""));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static String encode(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}
}
