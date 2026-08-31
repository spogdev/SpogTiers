package com.spog.tiers.client.gui;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.spog.tiers.SpogTiers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;

/**
 * Copies the profile to the clipboard as a picture.
 *
 * <p>The screen is drawn through a deferred extractor, so there is no
 * convenient way to re-run just part of it into an offscreen buffer. Instead
 * the screen hides everything that is not the profile for a single frame, and
 * this reads that frame back out of the framebuffer and crops it.
 *
 * <p>GLFW's clipboard carries text only, so the image goes through AWT's
 * clipboard, which understands image flavours.
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
		RenderTarget target = client.getMainRenderTarget();
		if (target == null || guiWidth <= 0 || guiHeight <= 0) {
			onDone.run();
			return;
		}

		double scale = client.getWindow().getGuiScale();

		Screenshot.takeScreenshot(target, image -> {
			try (NativeImage source = image) {
				BufferedImage cropped = crop(source, guiX, guiY, guiWidth, guiHeight, scale);
				if (cropped != null) {
					setClipboard(cropped);
				}
			} catch (Exception e) {
				SpogTiers.LOGGER.warn("Could not copy the profile image", e);
			} finally {
				onDone.run();
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

	private static void setClipboard(BufferedImage image) {
		// AWT starts its own threads, so this stays off the render thread's
		// critical path and out of the way if the toolkit is unavailable.
		try {
			System.setProperty("java.awt.headless", "false");
			Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
			clipboard.setContents(new ImageTransferable(image), null);
		} catch (Exception | Error e) {
			SpogTiers.LOGGER.warn("Clipboard unavailable", e);
		}
	}

	/** The one flavour AWT needs to hand an image to another application. */
	private record ImageTransferable(Image image) implements Transferable {
		@Override
		public DataFlavor[] getTransferDataFlavors() {
			return new DataFlavor[] {DataFlavor.imageFlavor};
		}

		@Override
		public boolean isDataFlavorSupported(DataFlavor flavor) {
			return DataFlavor.imageFlavor.equals(flavor);
		}

		@Override
		public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
			if (!DataFlavor.imageFlavor.equals(flavor)) {
				throw new UnsupportedFlavorException(flavor);
			}
			return image;
		}
	}
}
