package dev.spog.tiers.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.spog.tiers.SpogTiers;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Names the mod has seen, and who held them.
 *
 * <p>Mojang resolves only the name a player answers to <em>now</em>, so a
 * player who has renamed is unreachable by the name everyone knows them by.
 * No public service maps a freed name back to its old holder: laby.net has
 * the data but only behind a challenge-gated search endpoint, and Mojang's
 * own name-history API went away in 2022. Every third-party lookup resolves
 * through Mojang and so has nothing to say about a name nobody holds.
 *
 * <p>So the mod keeps its own index. Everything it already learns about a
 * player is recorded here -- the name they carry in the tab list, the name a
 * profile was opened under, and every name in the history the profile screen
 * fetches from laby -- and that is what {@code /tiers} falls back to when a
 * name resolves to nobody. It only knows players this installation has
 * actually come across, which is the price of there being no public index,
 * but it grows on its own as the mod is used.
 *
 * <p>Persisted, because the point is to remember a name long after the player
 * stopped using it: an index that emptied on restart would be useless by the
 * time anyone typed the old name.
 */
public final class NameIndex {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/**
	 * How many names to keep. Generous: an entry is a short string and a uuid,
	 * so even a full index is a few tens of kilobytes, and a name dropped is a
	 * lookup that silently stops working.
	 */
	private static final int MAX_ENTRIES = 20_000;

	/** Lowercased name to the player who held it. */
	private static final Map<String, UUID> BY_NAME = new ConcurrentHashMap<>();

	/** Set while a save is queued, so a burst of records writes once. */
	private static final AtomicBoolean DIRTY = new AtomicBoolean(false);

	private NameIndex() {
	}

	public static Path path() {
		return FabricLoader.getInstance().getConfigDir()
				.resolve(SpogTiers.MOD_ID + "-names.json");
	}

	/**
	 * Records that a name belonged to a player.
	 *
	 * <p>Last writer wins. A name can only be held by one account at a time,
	 * so the most recent sighting is the best answer -- and if the name has
	 * since been taken by somebody else, Mojang will resolve it and this is
	 * never consulted.
	 */
	public static void record(String name, UUID uuid) {
		if (name == null || name.isBlank() || uuid == null) {
			return;
		}
		String key = name.toLowerCase(Locale.ROOT);
		if (uuid.equals(BY_NAME.get(key))) {
			return;
		}
		// Dropped wholesale rather than evicting the oldest: there is no
		// access order to evict by, and the index rebuilds itself as the mod
		// is used. Reaching this at all takes twenty thousand distinct names.
		if (BY_NAME.size() >= MAX_ENTRIES && !BY_NAME.containsKey(key)) {
			SpogTiers.LOGGER.info("Name index full at {} entries, clearing", BY_NAME.size());
			BY_NAME.clear();
		}
		BY_NAME.put(key, uuid);
		DIRTY.set(true);
	}

	/** Records every name in a player's history, newest first. */
	public static void record(NameHistory history, UUID uuid) {
		if (history == null || uuid == null) {
			return;
		}
		for (NameHistory.Entry entry : history.entries()) {
			record(entry.name(), uuid);
		}
	}

	/** The player who held a name, or null if this installation never saw it. */
	public static UUID find(String name) {
		return name == null || name.isBlank()
				? null
				: BY_NAME.get(name.toLowerCase(Locale.ROOT));
	}

	public static int size() {
		return BY_NAME.size();
	}

	/** Reads the index. Call once at startup. */
	public static void load() {
		Path path = path();
		if (!Files.exists(path)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			Map<String, String> raw = GSON.fromJson(reader,
					new TypeToken<Map<String, String>>() {}.getType());
			if (raw == null) {
				return;
			}
			for (Map.Entry<String, String> entry : raw.entrySet()) {
				try {
					BY_NAME.put(entry.getKey().toLowerCase(Locale.ROOT),
							UUID.fromString(entry.getValue()));
				} catch (IllegalArgumentException e) {
					// One unparseable id is not a reason to lose the rest.
					SpogTiers.LOGGER.debug("Skipping bad name-index entry {}", entry.getKey());
				}
			}
			SpogTiers.LOGGER.debug("Name index loaded with {} names", BY_NAME.size());
		} catch (Exception e) {
			SpogTiers.LOGGER.warn("Could not read the name index, starting empty", e);
		}
	}

	/**
	 * Writes the index if anything changed.
	 *
	 * <p>Guarded on the dirty flag so this can be called freely -- on world
	 * exit, on quit -- without rewriting an unchanged file.
	 */
	public static void save() {
		if (!DIRTY.compareAndSet(true, false)) {
			return;
		}
		Map<String, String> raw = new java.util.TreeMap<>();
		for (Map.Entry<String, UUID> entry : BY_NAME.entrySet()) {
			raw.put(entry.getKey(), entry.getValue().toString());
		}
		Path path = path();
		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				GSON.toJson(raw, writer);
			}
		} catch (Exception e) {
			// Put the flag back, so a later save tries again.
			DIRTY.set(true);
			SpogTiers.LOGGER.warn("Could not write the name index", e);
		}
	}
}
