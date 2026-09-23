package dev.spog.tiers.client;

import dev.spog.tiers.SpogTiers;
import dev.spog.tiers.data.SkinHistory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SkinTextureDownloader;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads the skins from a player's {@link SkinHistory} so they can be worn by
 * the profile screen's model.
 *
 * <p>Vanilla's own {@link SkinTextureDownloader} does the work. It is not
 * exposed on {@link Minecraft}, but its constructor is public and everything it
 * needs is, so one is built here rather than reimplemented: it downloads,
 * converts the old 64x32 layout to 64x64, applies the transparency fix those
 * skins need, and registers the texture on the render thread. Writing that by
 * hand would be a second copy of rules that only vanilla really knows.
 *
 * <p>Each texture is loaded at most once per session and kept, because the
 * tiles and the model both draw from it and a profile is usually reopened.
 */
public final class SkinTextures {
	/** Where downloaded skins are cached on disk, under the game directory. */
	private static final String CACHE_DIR = "spogtiers-skins";

	/** In-flight and finished loads, by laby hash. */
	private static final Map<String, CompletableFuture<PlayerSkin>> LOADED =
			new ConcurrentHashMap<>();

	private static SkinTextureDownloader downloader;

	private SkinTextures() {
	}

	/**
	 * The skin for one history entry, loading it if this is the first ask.
	 *
	 * <p>Never blocks: an unfinished load reports itself through the future, and
	 * the caller keeps drawing whatever it has until then.
	 */
	public static CompletableFuture<PlayerSkin> get(SkinHistory.Entry entry) {
		return LOADED.computeIfAbsent(entry.hash(), hash -> load(entry));
	}

	/**
	 * Whether this entry's skin is ready to draw.
	 *
	 * <p>A load that failed counts as not ready, so a broken texture leaves the
	 * tile out rather than showing a missing-texture square.
	 */
	public static PlayerSkin ready(SkinHistory.Entry entry) {
		CompletableFuture<PlayerSkin> future = LOADED.get(entry.hash());
		if (future == null || !future.isDone() || future.isCompletedExceptionally()) {
			return null;
		}
		return future.getNow(null);
	}

	private static CompletableFuture<PlayerSkin> load(SkinHistory.Entry entry) {
		Minecraft client = Minecraft.getInstance();
		SkinTextureDownloader loader = downloader(client);
		if (loader == null) {
			return CompletableFuture.failedFuture(
					new IllegalStateException("no skin downloader"));
		}

		Identifier id = Identifier.fromNamespaceAndPath(SpogTiers.MOD_ID,
				"skins/" + entry.hash().toLowerCase(Locale.ROOT));
		Path cache = client.gameDirectory.toPath()
				.resolve(CACHE_DIR)
				.resolve(entry.hash());

		// The slim flag comes from laby rather than from the image: the two
		// models share one texture layout, so nothing in the PNG says which
		// arm width it was drawn for.
		PlayerModelType model = entry.slim() ? PlayerModelType.SLIM : PlayerModelType.WIDE;

		return loader.downloadAndRegisterSkin(id, cache, entry.url(), true)
				.thenApply(texture -> PlayerSkin.insecure(texture, null, null, model))
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
	private static SkinTextureDownloader downloader(Minecraft client) {
		if (downloader == null) {
			try {
				downloader = new SkinTextureDownloader(
						client.getProxy(), client.getTextureManager(), client);
			} catch (RuntimeException | LinkageError e) {
				SpogTiers.LOGGER.warn("Could not build the skin downloader; "
						+ "past skins will not be shown ({})", e.toString());
				return null;
			}
		}
		return downloader;
	}
}
