package com.spog.tiers.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * A square panel button carrying a refresh glyph instead of a label.
 *
 * <p>The arrow is drawn rather than blitted: it is a handful of fills, which
 * costs less than shipping and loading a texture for one small icon.
 */
public class IconButton extends AbstractButton {
	private static final int FILL = 0x60161B22;
	private static final int FILL_HOVERED = 0x8022303F;
	private static final int BORDER = 0x70323B47;
	private static final int BORDER_HOVERED = 0xA05B6B7D;
	private static final int ICON = 0xFFD7DEE6;
	private static final int ICON_HOVERED = 0xFFFFFFFF;

	private final Consumer<IconButton> onPress;

	public IconButton(int x, int y, int width, int height, Component message,
			Consumer<IconButton> onPress) {
		super(x, y, width, height, message);
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

		drawRefreshArrow(graphics, left + width / 2, top + height / 2,
				hovered ? ICON_HOVERED : ICON);
	}

	/**
	 * A circular arrow: a ring with a gap at the top right, and a head on the
	 * gap's leading edge so it reads as turning clockwise.
	 */
	private static void drawRefreshArrow(GuiGraphicsExtractor graphics, int cx, int cy, int color) {
		// Eight-point ring, minus the two cells where the gap goes.
		int[][] ring = {
			{-1, -3}, {0, -3},
			{2, -2},
			{3, -1}, {3, 0}, {3, 1},
			{2, 2},
			{1, 3}, {0, 3}, {-1, 3},
			{-2, 2},
			{-3, 1}, {-3, 0}, {-3, -1},
			{-2, -2},
		};
		for (int[] cell : ring) {
			graphics.fill(cx + cell[0], cy + cell[1], cx + cell[0] + 1, cy + cell[1] + 1, color);
		}

		// Arrowhead on the open end, pointing clockwise into the gap.
		int[][] head = {{1, -4}, {2, -4}, {2, -3}, {3, -3}, {1, -2}, {2, -2}};
		for (int[] cell : head) {
			graphics.fill(cx + cell[0], cy + cell[1], cx + cell[0] + 1, cy + cell[1] + 1, color);
		}
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		defaultButtonNarrationText(output);
	}
}
