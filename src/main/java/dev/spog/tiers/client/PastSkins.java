package dev.spog.tiers.client;

import dev.spog.tiers.SpogTiers;
import dev.spog.tiers.data.SkinHistory;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.PlayerSkinTextureDownloader;
import net.minecraft.util.Identifier;
import net.minecraft.entity.player.PlayerSkinType;
import net.minecraft.entity.player.SkinTextures;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads the skins from a player's {@link SkinHistory} so they can be worn by
 * the profile screen's model.
 *
 * <p>Vanilla's own {@link PlayerSkinTextureDownloader} does the work. It is not
 * exposed on {@link Minecraft}, but its constructor is public and everything it
 * needs is, so one is built here rather than reimplemented: it downloads,
 * converts the old 64x32 layout to 64x64, applies the transparency fix those
 * skins need, and registers the texture on the render thread. Writing that by
 * hand would be a second copy of rules that only vanilla really knows.
 *
 * <p>Each texture is loaded at most once per session and kept, because the
 * tiles and the model both draw from it and a profile is usually reopened.
 */
public final class PastSkins {
	/** Where downloaded skins are cached on disk, under the game directory. */
	private static final String CACHE_DIR = "spogtiers-skins";

	/** In-flight and finished loads, by laby hash. */
	private static final Map<String, CompletableFuture<SkinTextures>> LOADED =
			new ConcurrentHashMap<>();

	private static PlayerSkinTextureDownloader downloader;

	private PastSkins() {
	}

	/**
	 * The skin for one history entry, loading it if this is the first ask.
	 *
	 * <p>Never blocks: an unfinished load reports itself through the future, and
	 * the caller keeps drawing whatever it has until then.
	 */
	public static CompletableFuture<SkinTextures> get(SkinHistory.Entry entry) {
		return LOADED.computeIfAbsent(entry.hash(), hash -> load(entry));
	}

	/**
	 * Whether this entry's skin is ready to draw.
	 *
	 * <p>A load that failed counts as not ready, so a broken texture leaves the
	 * tile out rather than showing a missing-texture square.
	 */
	public static SkinTextures ready(SkinHistory.Entry entry) {
		CompletableFuture<SkinTextures> future = LOADED.get(entry.hash());
		if (future == null || !future.isDone() || future.isCompletedExceptionally()) {
			return null;
		}
		return future.getNow(null);
	}

	private static CompletableFuture<SkinTextures> load(SkinHistory.Entry entry) {
		MinecraftClient client = MinecraftClient.getInstance();
		PlayerSkinTextureDownloader loader = downloader(client);
		if (loader == null) {
			return CompletableFuture.failedFuture(
					new IllegalStateException("no skin downloader"));
		}

		Identifier id = Identifier.of(SpogTiers.MOD_ID,
				"skins/" + entry.hash().toLowerCase(Locale.ROOT));
		Path cache = client.runDirectory.toPath()
				.resolve(CACHE_DIR)
				.resolve(entry.hash());

		// The slim flag comes from laby rather than from the image: the two
		// models share one texture layout, so nothing in the PNG says which
		// arm width it was drawn for.
		PlayerSkinType model = entry.slim() ? PlayerSkinType.SLIM : PlayerSkinType.WIDE;

		return loader.downloadAndRegisterTexture(id, cache, entry.url(), true)
				.thenApply(texture -> SkinTextures.create(texture, null, null, model))
				.whenComplete((skin, error) -> {
					if (error != null) {
						SpogTiers.LOGGER.debug("Could not load skin {}", entry.hash(), error);
					}
				});
	}

	/**
	 * The shared downloader, built on first use.
	 *
	 * <p>Textures must be registered on the render thread, which is what the
	 * client itself is passed as here.
	 */
	private static PlayerSkinTextureDownloader downloader(MinecraftClient client) {
		if (downloader == null) {
			try {
				downloader = new PlayerSkinTextureDownloader(
						client.getNetworkProxy(), client.getTextureManager(), client);
			} catch (RuntimeException | LinkageError e) {
				SpogTiers.LOGGER.warn("Could not build the skin downloader; "
						+ "past skins will not be shown ({})", e.toString());
				return null;
			}
		}
		return downloader;
	}
}
