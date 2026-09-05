package com.spog.tiers.compat;

import com.spog.tiers.SpogTiers;
import com.spog.tiers.SpogTiersClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

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
	/**
	 * The alpha the mod caps a nameplate backdrop at.
	 *
	 * <p>Its own number, copied rather than guessed: above this it keeps the
	 * hue and forces the alpha down, which is what stops a solid plate from
	 * swallowing the name.
	 */
	private static final int ALPHA_CAP = 32;

	private static final String CONFIG_CLASS =
			"dev.microcontrollers.nametagtweaks.config.NametagTweaksConfig";

	/** Resolved once, on first use, and then reused. */
	private static boolean checked;
	private static boolean present;
	private static Object handler;
	private static Method instance;
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

	// NB: no offset accessor. The mod moves the plate by changing the y it
	// passes to the font, and our rows are positioned from that same argument
	// -- vanilla hands us the value it was given -- so a raised plate already
	// carries them. Adding it again shifted the rows off the plate and left
	// their backdrops showing as two bars either side of the name.


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
	 * <p>Returned as ARGB, and clamped the way the mod clamps it rather than
	 * taken whole: above an alpha of 32 it keeps the chosen hue but forces
	 * the alpha down to 32, and only at 32 or below does the colour pass
	 * through untouched. Reading {@code getRGB()} directly gave our rows the
	 * user's own alpha, so a half-transparent plate sat over rows that were
	 * either more solid or more faded than it.
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
			Class<?> colour = value.getClass();
			int alpha = (int) colour.getMethod("getAlpha").invoke(value);
			int argb = (int) colour.getMethod("getRGB").invoke(value);
			return alpha > ALPHA_CAP ? (ALPHA_CAP << 24) | (argb & 0xFFFFFF) : argb;
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
		Object options = Minecraft.getInstance().options;
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
