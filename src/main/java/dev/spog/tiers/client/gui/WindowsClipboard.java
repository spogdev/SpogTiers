package dev.spog.tiers.client.gui;

import dev.spog.tiers.SpogTiers;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Puts a picture on the Windows clipboard through the API itself.
 *
 * <p>The portable path shells out to the platform's clipboard tool, which on
 * Windows means starting PowerShell: about a second for the shell and another
 * half for {@code System.Windows.Forms}, for work that takes a tenth of a
 * second done directly. JNA is already on MinecraftClient's classpath, so the calls
 * cost nothing to reach.
 *
 * <p>Two formats go on the clipboard. PNG is what browsers and chat clients
 * ask for and it keeps the image exactly as encoded; CF_DIB is what Paint,
 * Office and older applications understand. Offering only one leaves the
 * picture unpasteable in half the places someone would want it.
 */
final class WindowsClipboard {
	private static final int CF_DIB = 8;
	/** A list of file names, which is what a file manager copies. */
	private static final int CF_HDROP = 15;
	private static final int GMEM_MOVEABLE = 0x0002;

	private WindowsClipboard() {
	}

	private interface User32 extends Library {
		User32 INSTANCE = Native.load("user32", User32.class);

		boolean OpenClipboard(Pointer owner);

		boolean EmptyClipboard();

		Pointer SetClipboardData(int format, Pointer handle);

		boolean CloseClipboard();

		int RegisterClipboardFormatA(String name);
	}

	private interface Kernel32 extends Library {
		Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class);

		Pointer GlobalAlloc(int flags, long bytes);

		Pointer GlobalLock(Pointer handle);

		boolean GlobalUnlock(Pointer handle);
	}

	/** True when the picture reached the clipboard. */
	static boolean put(BufferedImage image) {
		try {
			byte[] png = encodePng(image);
			byte[] dib = encodeDib(image);
			if (png == null) {
				return false;
			}

			int pngFormat = User32.INSTANCE.RegisterClipboardFormatA("PNG");
			// Allocated before the clipboard is opened, so it is held open for
			// as little time as possible -- no other application can use it
			// meanwhile.
			Pointer pngHandle = globalCopy(png);
			Pointer dibHandle = globalCopy(dib);

			if (!User32.INSTANCE.OpenClipboard(null)) {
				return false;
			}
			try {
				User32.INSTANCE.EmptyClipboard();
				if (pngFormat != 0) {
					User32.INSTANCE.SetClipboardData(pngFormat, pngHandle);
				}
				User32.INSTANCE.SetClipboardData(CF_DIB, dibHandle);
			} finally {
				User32.INSTANCE.CloseClipboard();
			}
			// The clipboard owns both blocks now; freeing them here would take
			// the picture away with them.
			return true;
		} catch (Exception | Error e) {
			// Anything at all -- a missing library, a denied clipboard -- falls
			// back to the portable path rather than losing the copy.
			SpogTiers.LOGGER.debug("Native clipboard unavailable", e);
			return false;
		}
	}

	/**
	 * Puts a file itself on the clipboard, the way a file manager does.
	 *
	 * <p>{@code CF_HDROP} is a DROPFILES header followed by the paths as
	 * wide characters, the list ending in a second null. Pasting it into a
	 * folder, a chat window or an upload box produces the file rather than a
	 * picture of it -- which is the difference between sending someone a skin
	 * they can apply and sending them a screenshot of one.
	 */
	static boolean putFile(java.nio.file.Path file) {
		try {
			String path = file.toAbsolutePath().toString();
			// DROPFILES is 20 bytes: the offset to the names, an unused point,
			// then two flags. fWide marks the names as UTF-16.
			byte[] names = path.getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
			ByteBuffer buffer = ByteBuffer
					.allocate(20 + names.length + 4)
					.order(ByteOrder.LITTLE_ENDIAN);
			buffer.putInt(20);
			buffer.putInt(0);
			buffer.putInt(0);
			buffer.putInt(0);
			buffer.putInt(1);
			buffer.put(names);
			// One null ends the name, a second ends the list.
			buffer.putShort((short) 0);
			buffer.putShort((short) 0);

			Pointer handle = globalCopy(buffer.array());
			if (!User32.INSTANCE.OpenClipboard(null)) {
				return false;
			}
			try {
				User32.INSTANCE.EmptyClipboard();
				User32.INSTANCE.SetClipboardData(CF_HDROP, handle);
			} finally {
				User32.INSTANCE.CloseClipboard();
			}
			return true;
		} catch (Exception | Error e) {
			SpogTiers.LOGGER.debug("Could not put a file on the clipboard", e);
			return false;
		}
	}

	private static byte[] encodePng(BufferedImage image) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		return ImageIO.write(image, "png", out) ? out.toByteArray() : null;
	}

	/**
	 * A BITMAPINFOHEADER followed by the pixels, which is what CF_DIB is.
	 *
	 * <p>Rows run bottom to top and each is padded to a multiple of four bytes,
	 * both of which the format requires.
	 */
	private static byte[] encodeDib(BufferedImage image) {
		int width = image.getWidth();
		int height = image.getHeight();
		int stride = ((width * 3 + 3) / 4) * 4;

		ByteBuffer buffer = ByteBuffer.allocate(40 + stride * height)
				.order(ByteOrder.LITTLE_ENDIAN);
		buffer.putInt(40);
		buffer.putInt(width);
		buffer.putInt(height);
		buffer.putShort((short) 1);
		buffer.putShort((short) 24);
		buffer.putInt(0);
		buffer.putInt(stride * height);
		// 72 DPI, in pixels per metre.
		buffer.putInt(2835);
		buffer.putInt(2835);
		buffer.putInt(0);
		buffer.putInt(0);

		byte[] row = new byte[stride];
		for (int y = height - 1; y >= 0; y--) {
			Arrays.fill(row, (byte) 0);
			for (int x = 0; x < width; x++) {
				int rgb = image.getRGB(x, y);
				row[x * 3] = (byte) (rgb & 0xFF);
				row[x * 3 + 1] = (byte) ((rgb >> 8) & 0xFF);
				row[x * 3 + 2] = (byte) ((rgb >> 16) & 0xFF);
			}
			buffer.put(row);
		}
		return buffer.array();
	}

	/** Copies bytes into a moveable global block, as the clipboard requires. */
	private static Pointer globalCopy(byte[] data) {
		Pointer handle = Kernel32.INSTANCE.GlobalAlloc(GMEM_MOVEABLE, data.length);
		Pointer target = Kernel32.INSTANCE.GlobalLock(handle);
		target.write(0, data, 0, data.length);
		Kernel32.INSTANCE.GlobalUnlock(handle);
		return handle;
	}
}
