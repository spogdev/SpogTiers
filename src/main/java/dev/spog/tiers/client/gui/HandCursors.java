package dev.spog.tiers.client.gui;

import com.mojang.blaze3d.platform.cursor.CursorType;
import dev.spog.tiers.SpogTiers;
import org.lwjgl.sdl.SDLMouse;
import org.lwjgl.sdl.SDLPixels;
import org.lwjgl.sdl.SDLSurface;
import org.lwjgl.sdl.SDL_Surface;
import org.lwjgl.system.MemoryUtil;

import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;

/**
 * The closed hand shown while a tag element is being held.
 *
 * <p>Drawn here rather than asked for by name because the platform's
 * standard set has no such shapes. It runs arrow, I-beam, crosshair,
 * pointing hand, the resizes and not-allowed, and stops -- open and grabbing
 * hands are CSS cursors, which it never adopted. So the two are built as
 * images and handed over as custom cursors.
 *
 * <p>Only the closed hand is drawn here. The open one was too, until it kept
 * looking wrong beside the system's own cursors -- there is no open hand to
 * copy, so the editor uses the pointing hand for "you can pick this up" and
 * keeps a drawn cursor only for "you are holding it", which no stock set has
 * at all.
 *
 * <p>Everything here is best-effort. A failure anywhere leaves
 * {@link #closed} null and the caller falls back to the pointing hand.
 */
public final class HandCursors {
	/** The size of each cursor, in pixels. */
	private static final int SIZE = 24;

	/**
	 * Where the click lands within the image.
	 *
	 * <p>The middle of the palm, which both hands share: the fingers move
	 * between the two and the palm does not, so the point under the pointer
	 * does not shift as the hand closes.
	 */
	private static final int HOT_X = 11;
	private static final int HOT_Y = 9;

	private static boolean built;
	private static CursorType closed;

	private HandCursors() {
	}

	/** The closed hand, for an element being held, or null. */
	public static CursorType closed() {
		build();
		return closed;
	}

	/**
	 * Builds both cursors once.
	 *
	 * <p>On the render thread, since that is the only thread that may talk to
	 * the windowing layer -- and it is where the only caller already is.
	 */
	private static void build() {
		if (built) {
			return;
		}
		built = true;
		try {
			// The constructor is private and there is no public way in that
			// takes a handle: createStandardCursor only accepts one of the
			// platform's own shape ids, and none is a hand of this kind.
			Constructor<CursorType> ctor =
					CursorType.class.getDeclaredConstructor(String.class, long.class);
			ctor.setAccessible(true);
			closed = make(ctor, "spogtiers_closed_hand");
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			SpogTiers.LOGGER.warn("Could not build the drag cursor; "
					+ "the pointing hand will be used instead ({})", e.toString());
			closed = null;
		}
	}

	/** One cursor, or null if SDL would not take it. */
	private static CursorType make(Constructor<CursorType> ctor, String name)
			throws ReflectiveOperationException {
		// Off the Java heap: SDL reads this buffer itself, and a heap buffer
		// has no address it could read from.
		ByteBuffer pixels = MemoryUtil.memAlloc(SIZE * SIZE * 4);
		try {
			paint(pixels);
			pixels.flip();
			// RGBA32 is the byte-order-correct alias, so the rows painted
			// above are read back as the same red, green, blue, alpha on
			// either endianness.
			SDL_Surface surface = SDLSurface.SDL_CreateSurfaceFrom(
					SIZE, SIZE, SDLPixels.SDL_PIXELFORMAT_RGBA32, pixels, SIZE * 4);
			if (surface == null) {
				return null;
			}
			try {
				long handle = SDLMouse.SDL_CreateColorCursor(surface, HOT_X, HOT_Y);
				return handle == 0L ? null : ctor.newInstance(name, handle);
			} finally {
				SDLSurface.SDL_DestroySurface(surface);
			}
		} finally {
			// SDL_CreateColorCursor copies the pixels, so the buffer is ours
			// to free as soon as the cursor exists.
			MemoryUtil.memFree(pixels);
		}
	}

	/**
	 * Paints one hand into the buffer, as RGBA rows top to bottom.
	 *
	 * <p>A white hand with a black outline, so it reads on both a bright sky
	 * and the dark panels behind the editor. The open hand has its fingers
	 * up; the closed one has them curled, and is a row shorter for it.
	 */
	private static void paint(ByteBuffer pixels) {
		// Each string is one row, and each character one pixel: a space is
		// clear, '#' the black outline and '.' the white fill. Written out
		// rather than computed because a hand is a shape, not a formula.
		String[] art = CLOSED;
		for (int y = 0; y < SIZE; y++) {
			String row = y < art.length ? art[y] : "";
			for (int x = 0; x < SIZE; x++) {
				char pixel = x < row.length() ? row.charAt(x) : ' ';
				switch (pixel) {
					case '#' -> put(pixels, 0, 0, 0, 255);
					case '.' -> put(pixels, 255, 255, 255, 255);
					default -> put(pixels, 0, 0, 0, 0);
				}
			}
		}
	}

	private static void put(ByteBuffer pixels, int r, int g, int b, int a) {
		pixels.put((byte) r).put((byte) g).put((byte) b).put((byte) a);
	}

	/** Fingers curled: this is being held. */
	private static final String[] CLOSED = {
		"                        ",
		"                        ",
		"        ##  ##  ##      ",
		"       #..##..##..#     ",
		"      #.............#   ",
		"     #..#..#..#..#..#   ",
		"   ###..............#   ",
		"  #..#..............#   ",
		"  #..#..............#   ",
		"  #..#..............#   ",
		"   #................#   ",
		"    #..............#    ",
		"    #..............#    ",
		"    #..............#    ",
		"     #............#     ",
		"     #...........#      ",
		"      #.........#       ",
		"      #.........#       ",
		"      ###########       ",
	};
}
