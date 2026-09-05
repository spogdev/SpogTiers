package com.spog.tiers.compat;

import com.spog.tiers.SpogTiers;
import com.spog.tiers.SpogTiersClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Reads Nametag Tweaks' settings, when it is installed.
 *
 * <p>That mod adjusts the nameplate itself -- its scale, its height above the
 * head, and the colour of the box behind it -- by wrapping vanilla's own
 * drawing. Our extra rows are drawn by our own code, so none of that reaches
 * them, and a tag whose middle line had moved or grown left the other two
 * behind. This reads the same values so the rows can follow.
 *
 * <p>Everything is reflective and every failure is silent. The mod is
 * optional, its config class is not API, and a tier tag that quietly stops
 * matching someone else's tweaks is a far better outcome than a crash on a
 * version where a field was renamed.
 *
 * <p>Values are re-read each frame rather than cached: the mod's config screen
 * applies changes immediately, and a cache would leave our rows a restart
 * behind.
 */
public final class NametagTweaks {
	private static final String MOD_ID = "nametagtweaks";
	private static final String CONFIG_CLASS =
			"dev.microcontrollers.nametagtweaks.config.NametagTweaksConfig";

	/** Resolved once, on first use, and then reused. */
	private static boolean checked;
	private static boolean present;
	private static Object handler;
	private static Method instance;
	private static Field offsetField;
	private static Field scaleField;
	private static Field colourField;
	private static Field shadowField;
	private static Field removeField;
	private static Field hidePlayersInHudField;

	private NametagTweaks() {
	}

	/** Whether the mod is installed and its settings could be read. */
	public static boolean present() {
		resolve();
		return present;
	}

	/** Whether the user wants our rows to follow it. */
	private static boolean following() {
		var config = SpogTiersClient.config();
		return config != null && config.matchNametagTweaks && present();
	}

	/**
	 * How far the nameplate has been moved, in font pixels.
	 *
	 * <p>The mod subtracts this straight from vanilla's label height, in the
	 * same units the label's own y is measured in, so it is applied inside
	 * the scaled frame rather than as a translation in blocks. Zero when the
	 * mod is absent or the user has turned matching off, so a caller can add
	 * it unconditionally.
	 */
	public static float offset() {
		if (!following()) {
			return 0.0f;
		}
		Object value = read(offsetField);
		return value instanceof Integer offset ? offset : 0.0f;
	}

	/** The scale the nameplate is drawn at, or 1 when nothing applies. */
	public static float scale() {
		if (!following()) {
			return 1.0f;
		}
		Object value = read(scaleField);
		// Guarded rather than trusted: a zero would collapse the rows to
		// nothing, and a negative would draw them inside out.
		if (value instanceof Float scale && scale > 0.0f) {
			return scale;
		}
		return 1.0f;
	}

	/**
	 * The backdrop colour to use behind a row, or {@code fallback} when the
	 * mod is absent or not being followed.
	 *
	 * <p>Returned as ARGB. The mod keeps an {@link java.awt.Color}, whose own
	 * alpha it honours, so a user who made the plate opaque gets opaque rows
	 * too rather than one solid line over two translucent ones.
	 */
	public static int background(int fallback) {
		if (!following()) {
			return fallback;
		}
		Object value = read(colourField);
		if (value == null) {
			return fallback;
		}
		try {
			Method rgb = value.getClass().getMethod("getRGB");
			return (int) rgb.invoke(value);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return fallback;
		}
	}

	/** Whether the mod wants a shadow under nameplate text. */
	public static boolean textShadow() {
		if (!following()) {
			return false;
		}
		return Boolean.TRUE.equals(read(shadowField));
	}

	/**
	 * Whether the extra rows should be suppressed for a player right now.
	 *
	 * <p>The mod hides the plate by refusing vanilla's own name-tag submit,
	 * which our rows never go through -- so without this they carried on
	 * drawing with no name between them, which is worse than showing nothing.
	 *
	 * <p>Only the player rules are consulted: our rows only ever hang off a
	 * player's tag, so the entity and armour-stand switches cannot apply.
	 */
	public static boolean hidden() {
		if (!following()) {
			return false;
		}
		if (Boolean.TRUE.equals(read(removeField))) {
			return true;
		}
		return hudHidden() && Boolean.TRUE.equals(read(hidePlayersInHudField));
	}

	/**
	 * Whether the HUD is hidden, as F1 does.
	 *
	 * <p>Read reflectively because the flag is not in the same place on every
	 * version this mod supports, and its absence should cost us this one rule
	 * rather than the whole integration.
	 */
	private static boolean hudHidden() {
		Object options = MinecraftClient.getInstance().options;
		if (options == null) {
			return false;
		}
		try {
			return options.getClass().getField("hideGui").getBoolean(options);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return false;
		}
	}

	/** Looks the mod up once, and remembers whether it is usable. */
	private static void resolve() {
		if (checked) {
			return;
		}
		checked = true;
		if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) {
			return;
		}
		try {
			Class<?> config = Class.forName(CONFIG_CLASS);
			handler = config.getField("CONFIG").get(null);
			instance = handler.getClass().getMethod("instance");
			instance.setAccessible(true);
			offsetField = config.getField("nametagOffset");
			scaleField = config.getField("nametagScale");
			colourField = config.getField("nametagColor");
			shadowField = config.getField("nametagTextShadow");
			removeField = config.getField("removeNametags");
			hidePlayersInHudField = config.getField("hidePlayerNametagsInHiddenHud");
			present = true;
			SpogTiers.LOGGER.info("Nametag Tweaks found; extra tag rows will follow it");
		} catch (ReflectiveOperationException | RuntimeException e) {
			// Installed but not in the shape we expect, which a version bump
			// can do at any time. Said once, at debug, and then left alone.
			SpogTiers.LOGGER.debug("Nametag Tweaks present but unreadable", e);
		}
	}

	/** One field off the mod's live config, or null if anything goes wrong. */
	private static Object read(Field field) {
		if (field == null || instance == null) {
			return null;
		}
		try {
			Object config = instance.invoke(handler);
			return config == null ? null : field.get(config);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}
}
