package dev.spog.tiers.client;

import dev.spog.tiers.SpogTiers;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Hands a skin to Essential's wardrobe.
 *
 * <p>Essential is not a dependency and is usually absent, so everything here
 * is reflective and every failure is quiet: the menu entry is only offered
 * when {@link #available()} says the classes are really there, and a change on
 * their side turns the entry off again rather than breaking the screen.
 *
 * <p>It opens Essential's own "Add Skin" modal with the file already chosen,
 * rather than writing to the library directly. Two reasons. The library is
 * held on Essential's servers, keyed by a texture hash that must exist on
 * Mojang's CDN -- a skin taken from somewhere else has no such hash until
 * Essential uploads it, which only their own flow does. And adding something
 * to someone's account is theirs to confirm: the modal is where they name it
 * and pick the model, and where they can say no.
 */
public final class EssentialSkins {
	/**
	 * Essential's mod id.
	 *
	 * <p>The loader knows it as {@code essential-container} -- the id of the
	 * loader shim that installs the real jar -- not as {@code essential}.
	 */
	private static final String MOD_ID = "essential-container";

	private static final String SKIN_MODAL = "gg.essential.gui.wardrobe.modals.SkinModal";
	private static final String GUI_UTIL = "gg.essential.util.GuiUtil";
	private static final String MODAL = "gg.essential.gui.common.modal.Modal";
	private static final String MODEL = "gg.essential.mod.Model";
	private static final String BITMAP = "gg.essential.util.image.bitmap.Bitmap";
	private static final String BITMAP_EXT =
			"gg.essential.util.image.bitmap.GuiEssentialExtensionsKt";
	private static final String PLATFORM = "gg.essential.util.GuiEssentialPlatform";

	/** Resolved once: null means unavailable, and the entry is not offered. */
	private static Boolean present;

	private EssentialSkins() {
	}

	/** Whether the wardrobe can be reached at all. */
	public static boolean available() {
		if (present == null) {
			present = resolve();
		}
		return present;
	}

	private static boolean resolve() {
		if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) {
			return false;
		}
		try {
			Class.forName(SKIN_MODAL);
			Class.forName(GUI_UTIL);
			Class.forName(BITMAP_EXT);
			Class.forName(PLATFORM);
			return true;
		} catch (ClassNotFoundException | LinkageError e) {
			// Installed, but not the shape this expects.
			SpogTiers.LOGGER.debug("Essential is present but its wardrobe is not", e);
			return false;
		}
	}

	/**
	 * Opens Essential's add-skin modal for a skin already saved to disk.
	 *
	 * @param file  the skin PNG, which must outlive the modal -- Essential
	 *              reads it when the user confirms, not when it opens
	 * @param name  what the skin is called to begin with; the user can change
	 *              it in the modal
	 * @param slim  whether it starts on the slim model
	 * @return whether the modal opened
	 */
	public static boolean add(Path file, String name, boolean slim) {
		if (!available()) {
			return false;
		}
		try {
			Class<?> modalClass = Class.forName(SKIN_MODAL);
			Class<?> guiUtil = Class.forName(GUI_UTIL);
			Class<?> modelClass = Class.forName(MODEL);

			Object companion = modalClass.getField("Companion").get(null);
			// GuiUtil is itself the ModalManager the modal is built against,
			// so it is both the manager passed in and the thing queued onto.
			Object util = guiUtil.getField("INSTANCE").get(null);
			Object model = enumValue(modelClass, slim ? "ALEX" : "STEVE");
			Object preview = registerPreview(file);
			if (model == null || preview == null) {
				return false;
			}

			Method addFile = null;
			for (Method method : companion.getClass().getMethods()) {
				if (method.getName().equals("addFile")
						&& method.getParameterCount() == 5) {
					addFile = method;
					break;
				}
			}
			if (addFile == null) {
				return false;
			}

			Object modal = addFile.invoke(companion, util, file, preview, name, model);

			// queueModal takes the modal itself; pushModal wants a Kotlin
			// lambda, which is far more awkward to build reflectively.
			Method queue = guiUtil.getMethod("queueModal", Class.forName(MODAL));
			queue.invoke(util, modal);
			return true;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			SpogTiers.LOGGER.warn("Could not open Essential's add-skin modal", e);
			return false;
		}
	}

	/**
	 * Registers the skin as a texture and returns Essential's handle to it.
	 *
	 * <p>The modal takes a {@code UIdentifier} for the picture it previews,
	 * and it is not optional -- Kotlin rejects a null there before the modal is
	 * ever built, which is why passing one did nothing at all. Essential builds
	 * it by loading the file, running it through their own skin preprocessing
	 * and registering the result, so that is what happens here.
	 */
	private static Object registerPreview(Path file) throws ReflectiveOperationException {
		try {
			Class<?> bitmapClass = Class.forName(BITMAP);
			Class<?> extensions = Class.forName(BITMAP_EXT);
			Class<?> platformClass = Class.forName(PLATFORM);

			Object bitmapCompanion = bitmapClass.getField("Companion").get(null);

			Object bitmap;
			try (InputStream in = Files.newInputStream(file)) {
				Method from = extensions.getMethod("fromOrThrow",
						bitmapCompanion.getClass(), InputStream.class);
				bitmap = from.invoke(null, bitmapCompanion, in);
			}
			if (bitmap == null) {
				return null;
			}

			// The same preprocessing Essential applies to a skin picked from
			// disk: it fixes the transparency the old layouts need.
			try {
				Class<?> skinUtils = Class.forName("gg.essential.gui.skin.SkinUtilsKt");
				Method preprocess = skinUtils.getMethod("preprocessSkinImage", bitmapClass);
				Object processed = preprocess.invoke(null, bitmap);
				if (processed != null) {
					bitmap = processed;
				}
			} catch (ReflectiveOperationException ignored) {
				// Preview only: an unprocessed picture still previews fine.
			}

			Method toTexture = extensions.getMethod("toTexture", bitmapClass);
			Object texture = toTexture.invoke(null, bitmap);

			Object platformCompanion = platformClass.getField("Companion").get(null);
			Method getPlatform = platformCompanion.getClass().getMethod("getPlatform");
			Object platform = getPlatform.invoke(platformCompanion);

			Method register = null;
			for (Method method : platformClass.getMethods()) {
				if (method.getName().equals("registerCosmeticTexture")
						&& method.getParameterCount() == 2) {
					register = method;
					break;
				}
			}
			if (register == null) {
				return null;
			}
			return register.invoke(platform, "spogtiers-skin", texture);
		} catch (IOException | RuntimeException | LinkageError e) {
			SpogTiers.LOGGER.warn("Could not prepare the skin preview for Essential", e);
			return null;
		}
	}

	/** One constant of an enum class, by name, or null if it has moved. */
	private static Object enumValue(Class<?> type, String name) {
		try {
			Field field = type.getField(name);
			return field.get(null);
		} catch (ReflectiveOperationException e) {
			return null;
		}
	}
}
