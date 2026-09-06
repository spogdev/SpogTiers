package com.spog.tiers.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.spog.tiers.SpogTiers;
import com.spog.tiers.data.Gamemode;
import com.spog.tiers.data.TierList;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterOutputStream;

/**
 * A shareable code carrying one player's tag layout and the settings that
 * shape it.
 *
 * <p>The point is that a code outlives the version it was written by. Someone
 * on an older build has to be able to read a code from a newer one, and the
 * other way round, for as long as the two builds still mean the same thing by
 * a nametag. Three rules keep that true:
 *
 * <ul>
 *   <li><b>Names, never ordinals.</b> Every enum travels as its own name, so
 *       inserting a value into {@link TierList} or {@link Gamemode} cannot
 *       silently turn someone's PvPTiers element into a SubTiers one. An
 *       unknown name is dropped rather than guessed at.
 *   <li><b>Unknown fields are ignored, missing fields keep their default.</b>
 *       A newer build writing a setting this one has never heard of costs
 *       nothing to read, and a setting added since the code was written keeps
 *       whatever this build ships as its default.
 *   <li><b>One number gates the whole thing.</b> {@link #FORMAT} only rises if
 *       a code stops being readable at all -- not for adding a field, which
 *       the two rules above already cover. A code from the future is refused
 *       with an explanation instead of being half-applied.
 * </ul>
 *
 * <p>What is deliberately <em>not</em> in a code: cache and request tuning,
 * which is about someone's connection rather than their setup, and anything
 * identifying who exported it.
 */
public final class LayoutCode {
	/**
	 * How the payload is shaped, not what it contains.
	 *
	 * <p>Raised only for a change that makes an old reader unable to make
	 * sense of a code at all. Adding settings does not qualify: an older build
	 * skips what it does not know, and a newer one defaults what is absent.
	 */
	private static final int FORMAT = 1;

	/**
	 * Marks a string as one of ours, and says which format follows.
	 *
	 * <p>In the text rather than the payload so a wrong or truncated paste can
	 * be told apart from a code this build is too old for, and so the reason
	 * given to the user is the true one.
	 */
	private static final String PREFIX = "SPOGTAG";

	private static final Gson GSON = new GsonBuilder().create();

	private LayoutCode() {
	}

	/** What a code failed to be, for a message the user can act on. */
	public enum Problem {
		/** Not one of our codes at all: a stray paste, or truncated. */
		NOT_A_CODE("That is not a SpogTiers layout code"),
		/** Ours, but written by a build whose format this one cannot read. */
		TOO_NEW("That code is from a newer version of SpogTiers than this one"),
		/** Ours and the right format, but the payload is damaged. */
		DAMAGED("That code is incomplete or damaged");

		private final String message;

		Problem(String message) {
			this.message = message;
		}

		public String message() {
			return message;
		}
	}

	/** Either the settings a code carried, or why it could not be read. */
	public record Result(JsonObject payload, Problem problem) {
		public boolean ok() {
			return payload != null;
		}
	}

	/**
	 * Writes the current layout and the settings that shape it as one code.
	 *
	 * <p>Deflated and Base64'd: a layout of any size is a few hundred bytes of
	 * JSON, and a code someone has to paste into chat wants to be shorter than
	 * that. The URL-safe alphabet without padding keeps it in one piece
	 * wherever it is pasted -- Discord, a browser bar, a server's chat.
	 */
	public static String write(SpogTiersConfig config) {
		JsonObject root = new JsonObject();
		root.add("layout", GSON.toJsonTree(config.tagLayout));
		root.add("settings", settings(config));

		byte[] json = GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
		ByteArrayOutputStream packed = new ByteArrayOutputStream();
		try (DeflaterOutputStream deflater = new DeflaterOutputStream(packed)) {
			deflater.write(json);
		} catch (java.io.IOException e) {
			// Deflating a byte array in memory cannot fail for any reason the
			// user could act on, so the code is simply not offered.
			SpogTiers.LOGGER.warn("Could not write a layout code", e);
			return null;
		}
		return PREFIX + FORMAT + "-"
				+ Base64.getUrlEncoder().withoutPadding().encodeToString(packed.toByteArray());
	}

	/**
	 * Reads a code, or says why it could not be.
	 *
	 * <p>Whitespace is stripped first: a code that has been through chat or an
	 * email tends to arrive with a line break in the middle of it, and
	 * refusing that would look like the code was bad.
	 */
	public static Result read(String code) {
		if (code == null) {
			return new Result(null, Problem.NOT_A_CODE);
		}
		String trimmed = code.replaceAll("\\s", "");
		if (!trimmed.startsWith(PREFIX)) {
			return new Result(null, Problem.NOT_A_CODE);
		}
		int dash = trimmed.indexOf('-');
		if (dash < 0) {
			return new Result(null, Problem.NOT_A_CODE);
		}
		int format;
		try {
			format = Integer.parseInt(trimmed.substring(PREFIX.length(), dash));
		} catch (NumberFormatException e) {
			return new Result(null, Problem.NOT_A_CODE);
		}
		if (format > FORMAT) {
			return new Result(null, Problem.TOO_NEW);
		}

		try {
			byte[] packed = Base64.getUrlDecoder().decode(trimmed.substring(dash + 1));
			ByteArrayOutputStream json = new ByteArrayOutputStream();
			try (InflaterOutputStream inflater = new InflaterOutputStream(json)) {
				inflater.write(packed);
			}
			JsonElement parsed = JsonParser.parseString(
					json.toString(StandardCharsets.UTF_8));
			if (!parsed.isJsonObject()) {
				return new Result(null, Problem.DAMAGED);
			}
			return new Result(parsed.getAsJsonObject(), null);
		} catch (IllegalArgumentException | java.io.IOException
				| com.google.gson.JsonParseException e) {
			return new Result(null, Problem.DAMAGED);
		}
	}

	/**
	 * Applies a code that {@link #read} accepted, and says what was dropped.
	 *
	 * <p>Everything is applied field by field rather than by deserialising
	 * over the config, so a code written by a newer build cannot introduce a
	 * setting this one does not understand, and a code missing a field leaves
	 * that field alone. The layout is normalised afterwards, so a code that
	 * has lost its name element or names a tier list this build does not have
	 * still lands as something drawable.
	 *
	 * @return a note on what could not be carried across, or null when all of
	 *     it applied
	 */
	public static String apply(JsonObject payload, SpogTiersConfig config) {
		List<String> dropped = new ArrayList<>();

		JsonElement layout = payload.get("layout");
		if (layout != null && layout.isJsonObject()) {
			config.tagLayout = layout(layout.getAsJsonObject(), dropped);
		}

		JsonElement settings = payload.get("settings");
		if (settings != null && settings.isJsonObject()) {
			settings(settings.getAsJsonObject(), config);
		}

		config.normalise();
		config.save();
		return dropped.isEmpty() ? null
				: "Imported, without " + String.join(", ", dropped);
	}

	/** The settings a code carries, by name. */
	private static JsonObject settings(SpogTiersConfig config) {
		JsonObject out = new JsonObject();
		out.addProperty("showNametags", config.showNametags);
		out.addProperty("showTabList", config.showTabList);
		out.addProperty("showInChat", config.showInChat);
		out.addProperty("tagDisplays", config.tagDisplays);
		out.addProperty("showBestTier", config.showBestTier);
		out.addProperty("showRetired", config.showRetired);
		out.addProperty("showPlacements", config.showPlacements);
		out.addProperty("showParticles", config.showParticles);
		out.addProperty("showSeparators", config.showSeparators);
		out.addProperty("preventDuplicateTiers", config.preventDuplicateTiers);
		out.addProperty("extraTierlists", config.extraTierlists);
		out.addProperty("displayList", config.displayList.name());
		out.addProperty("displayMode", config.displayMode.name());
		if (config.regionSlot != null) {
			out.addProperty("regionSlot", config.regionSlot.name());
		}
		out.addProperty("sortOrder", config.sortOrder.name());
		out.add("taggedLists", lists(config.taggedLists));
		out.add("enabledLists", lists(config.enabledLists));
		return out;
	}

	/** Per-list switches, keyed by name so an added list cannot shift them. */
	private static JsonObject lists(java.util.Map<TierList, Boolean> from) {
		JsonObject out = new JsonObject();
		if (from != null) {
			from.forEach((list, on) -> {
				if (list != null && on != null) {
					out.addProperty(list.name(), on);
				}
			});
		}
		return out;
	}

	/** Reads the settings a code carries, leaving absent ones alone. */
	private static void settings(JsonObject from, SpogTiersConfig config) {
		config.showNametags = bool(from, "showNametags", config.showNametags);
		config.showTabList = bool(from, "showTabList", config.showTabList);
		config.showInChat = bool(from, "showInChat", config.showInChat);
		config.tagDisplays = bool(from, "tagDisplays", config.tagDisplays);
		config.showBestTier = bool(from, "showBestTier", config.showBestTier);
		config.showRetired = bool(from, "showRetired", config.showRetired);
		config.showPlacements = bool(from, "showPlacements", config.showPlacements);
		config.showParticles = bool(from, "showParticles", config.showParticles);
		config.showSeparators = bool(from, "showSeparators", config.showSeparators);
		config.preventDuplicateTiers =
				bool(from, "preventDuplicateTiers", config.preventDuplicateTiers);
		config.extraTierlists = bool(from, "extraTierlists", config.extraTierlists);

		TierList list = value(TierList.class, from, "displayList");
		if (list != null) {
			config.displayList = list;
		}
		Gamemode mode = value(Gamemode.class, from, "displayMode");
		if (mode != null) {
			config.displayMode = mode;
		}
		SpogTiersConfig.RegionSlot slot =
				value(SpogTiersConfig.RegionSlot.class, from, "regionSlot");
		if (slot != null) {
			config.regionSlot = slot;
		}
		SpogTiersConfig.SortOrder order =
				value(SpogTiersConfig.SortOrder.class, from, "sortOrder");
		if (order != null) {
			config.sortOrder = order;
		}
		lists(from, "taggedLists", config.taggedLists);
		lists(from, "enabledLists", config.enabledLists);
	}

	/** Reads per-list switches, ignoring lists this build does not have. */
	private static void lists(JsonObject from, String key,
			java.util.Map<TierList, Boolean> into) {
		JsonElement element = from.get(key);
		if (element == null || !element.isJsonObject() || into == null) {
			return;
		}
		for (var entry : element.getAsJsonObject().entrySet()) {
			TierList list = constant(TierList.class, entry.getKey());
			if (list != null && entry.getValue().isJsonPrimitive()) {
				into.put(list, entry.getValue().getAsBoolean());
			}
		}
	}

	/**
	 * Rebuilds the layout, dropping elements this build cannot draw.
	 *
	 * <p>Element by element rather than through Gson, because an element
	 * naming a tier list or gamemode that does not exist here would otherwise
	 * deserialise to null and leave a blank in the middle of someone's tag.
	 */
	private static TagLayout layout(JsonObject from, List<String> dropped) {
		TagLayout layout = new TagLayout();
		layout.centerOnName = bool(from, "centerOnName", false);

		JsonElement elements = from.get("elements");
		if (elements == null || !elements.isJsonArray()) {
			return layout;
		}
		for (JsonElement each : elements.getAsJsonArray()) {
			if (!each.isJsonObject()) {
				continue;
			}
			JsonObject object = each.getAsJsonObject();
			TagLayout.Kind kind = value(TagLayout.Kind.class, object, "kind");
			if (kind == null) {
				// A kind this build has never heard of: the rest of the tag is
				// still worth having, so only this piece is lost.
				note(dropped, "an element this version does not have");
				continue;
			}
			TagLayout.Element element = new TagLayout.Element();
			element.kind = kind;
			TagLayout.Row row = value(TagLayout.Row.class, object, "row");
			element.row = row == null ? TagLayout.Row.MIDDLE : row;
			element.best = bool(object, "best", false);
			element.doorSmp = bool(object, "doorSmp", false);
			element.character = string(object, "character", "|");
			element.colour = number(object, "colour", 0x555555);

			TierList list = value(TierList.class, object, "list");
			if (kind == TagLayout.Kind.TIER && !element.best && !element.doorSmp) {
				if (list == null) {
					// The element named a list this build does not have. Best
					// across the lists it does have is the closest honest
					// reading of "a tier here".
					note(dropped, "a tier list this version does not have");
					element.best = true;
				} else {
					element.list = list;
				}
			} else if (list != null) {
				element.list = list;
			}
			element.gamemode = value(Gamemode.class, object, "gamemode");
			layout.elements.add(element);
		}
		return layout;
	}

	private static void note(List<String> dropped, String what) {
		if (!dropped.contains(what)) {
			dropped.add(what);
		}
	}

	/** One enum constant by name, or null when this build has no such value. */
	private static <E extends Enum<E>> E value(Class<E> type, JsonObject from, String key) {
		JsonElement element = from.get(key);
		if (element == null || !element.isJsonPrimitive()) {
			return null;
		}
		return constant(type, element.getAsString());
	}

	private static <E extends Enum<E>> E constant(Class<E> type, String name) {
		try {
			return Enum.valueOf(type, name);
		} catch (IllegalArgumentException | NullPointerException e) {
			return null;
		}
	}

	private static boolean bool(JsonObject from, String key, boolean fallback) {
		JsonElement element = from.get(key);
		return element != null && element.isJsonPrimitive()
				? element.getAsBoolean() : fallback;
	}

	private static int number(JsonObject from, String key, int fallback) {
		JsonElement element = from.get(key);
		try {
			return element != null && element.isJsonPrimitive()
					? element.getAsInt() : fallback;
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static String string(JsonObject from, String key, String fallback) {
		JsonElement element = from.get(key);
		return element != null && element.isJsonPrimitive()
				? element.getAsString() : fallback;
	}
}
