package dev.spog.tiers.client.gui;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.spog.tiers.SpogTiers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Copies the profile to the clipboard as a picture.
 *
 * <p>The screen is drawn through a deferred extractor, so there is no
 * convenient way to re-run just part of it into an offscreen buffer. Instead
 * the screen hides everything that is not the profile for a single frame, and
 * this reads that frame back out of the framebuffer and crops it.
 *
 * <p>GLFW's clipboard carries text only and AWT's is unavailable in a
 * headless JVM, so the picture goes out through the platform's own
 * clipboard tool.
 */
public final class ProfileExport {
	/** Drawn behind the capture, so no transparency reaches the clipboard. */
	private static final int BACKGROUND = 0xFF0B0E13;

	private ProfileExport() {
	}

	/**
	 * Reads the current frame and copies the given GUI-space rectangle.
	 *
	 * <p>Coordinates are in GUI space; the framebuffer is larger by the GUI
	 * scale, so they are scaled up before cropping.
	 */
	public static void copy(int guiX, int guiY, int guiWidth, int guiHeight, Runnable onDone) {
		Minecraft client = Minecraft.getInstance();
		// 26.2 moved the main render target off Minecraft onto GameRenderer.
		RenderTarget target = client.gameRenderer.mainRenderTarget();
		if (target == null || guiWidth <= 0 || guiHeight <= 0) {
			onDone.run();
			return;
		}

		double scale = client.getWindow().getGuiScale();

		Screenshot.takeScreenshot(target, image -> {
			BufferedImage cropped = null;
			try (NativeImage source = image) {
				cropped = crop(source, guiX, guiY, guiWidth, guiHeight, scale);
			} catch (Exception e) {
				SpogTiers.LOGGER.warn("Could not read the profile image", e);
			} finally {
				// The screen goes back to normal as soon as the pixels are
				// out; encoding and the clipboard tool are not worth freezing
				// a frame for.
				onDone.run();
			}

			if (cropped != null) {
				BufferedImage finished = cropped;
				Thread worker = new Thread(() -> setClipboard(finished),
						"SpogTiers Clipboard");
				worker.setDaemon(true);
				worker.start();
			}
		});
	}

	/** Crops the framebuffer to the rectangle, flattened onto a solid fill. */
	private static BufferedImage crop(NativeImage source, int guiX, int guiY,
			int guiWidth, int guiHeight, double scale) {
		int left = (int) Math.floor(guiX * scale);
		int top = (int) Math.floor(guiY * scale);
		int right = (int) Math.ceil((guiX + guiWidth) * scale);
		int bottom = (int) Math.ceil((guiY + guiHeight) * scale);

		// The window can be resized between the request and the callback.
		left = Math.clamp(left, 0, source.getWidth());
		top = Math.clamp(top, 0, source.getHeight());
		right = Math.clamp(right, left, source.getWidth());
		bottom = Math.clamp(bottom, top, source.getHeight());

		int width = right - left;
		int height = bottom - top;
		if (width <= 0 || height <= 0) {
			return null;
		}

		BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				// getPixel hands back ARGB here, not the ABGR the name of the
				// sibling setPixelABGR suggests: reading it the other way round
				// swapped red and blue and turned the skin blue.
				int argb = source.getPixel(left + x, top + y);
				int red = (argb >> 16) & 0xFF;
				int green = (argb >> 8) & 0xFF;
				int blue = argb & 0xFF;
				int alpha = (argb >>> 24) & 0xFF;
				out.setRGB(x, y, alpha == 0xFF
						? (red << 16) | (green << 8) | blue
						: blend(red, green, blue, alpha));
			}
		}
		return out;
	}

	/** Composites a pixel over the solid backdrop. */
	private static int blend(int red, int green, int blue, int alpha) {
		int backRed = (BACKGROUND >> 16) & 0xFF;
		int backGreen = (BACKGROUND >> 8) & 0xFF;
		int backBlue = BACKGROUND & 0xFF;
		int outRed = (red * alpha + backRed * (255 - alpha)) / 255;
		int outGreen = (green * alpha + backGreen * (255 - alpha)) / 255;
		int outBlue = (blue * alpha + backBlue * (255 - alpha)) / 255;
		return (outRed << 16) | (outGreen << 8) | outBlue;
	}

	/**
	 * Hands the picture to the system clipboard.
	 *
	 * <p>Not through AWT: Minecraft starts the JVM with
	 * {@code java.awt.headless=true}, so {@code getSystemClipboard} throws
	 * {@link java.awt.HeadlessException}. That cannot be undone from inside the
	 * game -- clearing the cached flag needs reflection into {@code java.awt},
	 * which the module system refuses without an {@code --add-opens} nobody
	 * launching Minecraft is going to have.
	 *
	 * <p>Encoding a PNG needs no display, so the picture is written to a
	 * temporary file and the platform's own clipboard tool is asked to put it
	 * on the clipboard.
	 */
	private static void setClipboard(BufferedImage image) {
		// Windows can be done in-process. Spawning PowerShell cost about a
		// second and a half -- roughly a second of that is the shell starting
		// up and half a second loading System.Windows.Forms -- against about a
		// tenth of a second calling the clipboard API directly.
		if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
				&& WindowsClipboard.put(image)) {
			return;
		}

		Path file = null;
		try {
			file = Files.createTempFile("spogtiers-profile", ".png");
			if (!ImageIO.write(image, "png", file.toFile())) {
				SpogTiers.LOGGER.warn("No PNG encoder available");
				return;
			}

			List<String> command = clipboardCommand(file);
			if (command == null) {
				SpogTiers.LOGGER.warn("No clipboard tool for this platform");
				return;
			}

			Process process = new ProcessBuilder(command)
					.redirectErrorStream(true)
					.start();
			if (!process.waitFor(15, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				SpogTiers.LOGGER.warn("Clipboard tool timed out");
				return;
			}
			if (process.exitValue() != 0) {
				String output = new String(process.getInputStream().readAllBytes(),
						StandardCharsets.UTF_8).trim();
				SpogTiers.LOGGER.warn("Clipboard tool failed ({}): {}",
						process.exitValue(), output);
			}
		} catch (Exception e) {
			SpogTiers.LOGGER.warn("Could not reach the clipboard", e);
		} finally {
			// The tool has read the file by the time it exits; on Windows the
			// clipboard keeps its own copy, so this is safe to remove.
			if (file != null) {
				try {
					Files.deleteIfExists(file);
				} catch (Exception ignored) {
					// A leftover temp file is not worth reporting.
				}
			}
		}
	}

	/** The command that puts an image file on this platform's clipboard. */
	private static List<String> clipboardCommand(Path file) {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String path = file.toAbsolutePath().toString();

		if (os.contains("win")) {
			// SetDataObject with copy=true leaves the image on the clipboard
			// after PowerShell exits; PNG is offered alongside the bitmap so
			// applications that prefer it (browsers, chat clients) get it.
			String script = "Add-Type -AssemblyName System.Windows.Forms,System.Drawing;"
					+ "$bytes=[System.IO.File]::ReadAllBytes('" + path + "');"
					+ "$ms=New-Object System.IO.MemoryStream(,$bytes);"
					+ "$img=[System.Drawing.Image]::FromStream($ms);"
					+ "$data=New-Object System.Windows.Forms.DataObject;"
					+ "$data.SetData('PNG',$false,$ms);"
					+ "$data.SetImage($img);"
					+ "[System.Windows.Forms.Clipboard]::SetDataObject($data,$true);";
			return List.of("powershell", "-NoProfile", "-STA", "-Command", script);
		}
		if (os.contains("mac")) {
			return List.of("osascript", "-e",
					"set the clipboard to (read (POSIX file \"" + path + "\") as «class PNGf»)");
		}
		// Wayland and X11 respectively; whichever is installed will run.
		if (System.getenv("WAYLAND_DISPLAY") != null) {
			return List.of("sh", "-c",
					"wl-copy --type image/png < '" + path + "'");
		}
		return List.of("sh", "-c",
				"xclip -selection clipboard -t image/png -i '" + path + "'");
	}
}
