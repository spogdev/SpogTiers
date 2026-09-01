package com.spog.tiers.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * A square panel button carrying a small glyph instead of a label.
 *
 * <p>The glyphs are drawn rather than blitted: they are a handful of fills,
 * which costs less than shipping and loading a texture per icon.
 */
public class IconButton extends AbstractButton {
	private static final int FILL = 0x60161B22;
	private static final int FILL_HOVERED = 0x8022303F;
	private static final int BORDER = 0x70323B47;
	private static final int BORDER_HOVERED = 0xA05B6B7D;
	private static final int ICON = 0xFFD7DEE6;
	private static final int ICON_HOVERED = 0xFFFFFFFF;

	/** Which glyph a button wears. */
	public enum Glyph {
		REFRESH,
		COPY
	}

	private final Consumer<IconButton> onPress;
	private final Glyph glyph;

	public IconButton(int x, int y, int width, int height, Component message,
			Consumer<IconButton> onPress) {
		this(x, y, width, height, message, Glyph.REFRESH, onPress);
	}

	public IconButton(int x, int y, int width, int height, Component message,
			Glyph glyph, Consumer<IconButton> onPress) {
		super(x, y, width, height, message);
		this.glyph = glyph;
		this.onPress = onPress;
	}

	@Override
	public void onPress(InputWithModifiers input) {
		onPress.accept(this);
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			float partialTick) {
		int left = getX();
		int top = getY();
		int right = left + width;
		int bottom = top + height;

		boolean hovered = isHovered;
		graphics.fill(left, top, right, bottom, hovered ? FILL_HOVERED : FILL);
		int border = hovered ? BORDER_HOVERED : BORDER;
		graphics.fill(left, top, right, top + 1, border);
		graphics.fill(left, bottom - 1, right, bottom, border);
		graphics.fill(left, top, left + 1, bottom, border);
		graphics.fill(right - 1, top, right, bottom, border);

		int color = hovered ? ICON_HOVERED : ICON;
		int cx = left + width / 2;
		int cy = top + height / 2;
		switch (glyph) {
			case REFRESH -> drawRefreshArrow(graphics, cx, cy, color);
			case COPY -> drawCopyGlyph(graphics, cx, cy, color);
		}
	}

	/**
	 * A circular arrow: a ring with a gap at the top right, and a head on the
	 * gap's leading edge so it reads as turning clockwise.
	 *
	 * <p>Sized to sit level with the copy glyph beside it -- the earlier ring
	 * was a couple of pixels smaller all round, which read as a mistake rather
	 * than a difference.
	 */
	private static void drawRefreshArrow(GuiGraphicsExtractor graphics, int cx, int cy, int color) {
		int[][] ring = {
			{-3, -4}, {-2, -4}, {-1, -4}, {0, -4}, {1, -4}, {2, -4},
			{-4, -3}, {-3, -3},
			{-4, -2},
			{-4, -1},
			{-4, 0},
			{-4, 1}, {4, 1},
			{-4, 2}, {4, 2},
			{-4, 3}, {-3, 3}, {3, 3}, {4, 3},
			{-3, 4}, {-2, 4}, {-1, 4}, {0, 4}, {1, 4}, {2, 4}, {3, 4},
		};
		for (int[] cell : ring) {
			graphics.fill(cx + cell[0], cy + cell[1], cx + cell[0] + 1, cy + cell[1] + 1, color);
		}

		// Arrowhead on the open end, pointing clockwise into the gap.
		int[][] head = {
			{2, -6}, {3, -6},
			{2, -5}, {3, -5}, {4, -5},
			{2, -4}, {3, -4}, {4, -4}, {5, -4},
			{3, -3}, {4, -3},
			{3, -2},
		};
		for (int[] cell : head) {
			graphics.fill(cx + cell[0], cy + cell[1], cx + cell[0] + 1, cy + cell[1] + 1, color);
		}
	}

	/**
	 * Two overlapping page outlines, the usual shorthand for "copy".
	 *
	 * <p>Both are hollow so the glyph stays readable at this size; a filled
	 * pair turned into an unreadable blob.
	 */
	private static void drawCopyGlyph(GuiGraphicsExtractor graphics, int cx, int cy, int color) {
		// Back page, up and to the right.
		outline(graphics, cx - 1, cy - 5, 7, 9, color);
		// Front page, down and to the left, cleared first so the two read as
		// separate sheets rather than a grid.
		graphics.fill(cx - 5, cy - 2, cx + 2, cy + 6, 0xFF10151C);
		outline(graphics, cx - 5, cy - 2, 7, 8, color);
	}

	/** A one-pixel rectangle border. */
	private static void outline(GuiGraphicsExtractor graphics, int x, int y,
			int width, int height, int color) {
		graphics.fill(x, y, x + width, y + 1, color);
		graphics.fill(x, y + height - 1, x + width, y + height, color);
		graphics.fill(x, y, x + 1, y + height, color);
		graphics.fill(x + width - 1, y, x + width, y + height, color);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		defaultButtonNarrationText(output);
	}
}
