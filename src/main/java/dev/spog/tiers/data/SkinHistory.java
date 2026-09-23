package dev.spog.tiers.data;

import java.util.List;

/**
 * A player's past skins, the one they wear now first.
 *
 * <p>Mojang publishes only the active skin, so this comes from laby.net -- the
 * same service the mod already reads {@link NameHistory} from. Its records
 * begin when it started watching rather than at account creation, so an old
 * account can have far fewer skins here than it has actually worn.
 */
public record SkinHistory(List<Entry> entries) {
	public static final SkinHistory EMPTY = new SkinHistory(List.of());

	/**
	 * One skin.
	 *
	 * @param hash      laby's content hash, which is also its texture's name
	 * @param slim      whether it is worn on the slim (Alex) model
	 * @param active    whether this is the skin the player wears now
	 * @param firstSeen when laby first saw it, in epoch seconds; 0 if unknown
	 * @param lastSeen  when laby last saw it, in epoch seconds; 0 if unknown
	 */
	public record Entry(String hash, boolean slim, boolean active,
			long firstSeen, long lastSeen) {
		/**
		 * Where the texture is served from.
		 *
		 * <p>The hash is repeated as a query string on purpose. laby stores
		 * every texture as a PNG, but Cloudflare Polish sits in front and has
		 * cached WebP variants of some of them -- and {@code NativeImage}
		 * cannot read WebP. A query string Polish has no variant for misses
		 * that cache and comes from the origin, which is always the PNG. It is
		 * the hash rather than a nonce so the URL stays stable and cacheable.
		 */
		public String url() {
			return "https://laby.net/texture/" + hash + ".png?spogtiers=" + hash;
		}
	}

	public boolean isEmpty() {
		return entries.isEmpty();
	}
}
