package com.spog.tiers.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * A flat button drawn in the profile panel's own style, so it sits with the
 * cards instead of looking like a stock Minecraft widget.
 */
public class PanelButton extends AbstractButton {
	private static final int FILL = 0x60161B22;
	private static final int FILL_HOVERED = 0x8022303F;
	private static final int BORDER = 0x70323B47;
	private static final int BORDER_HOVERED = 0xA05B6B7D;
	private static final int TEXT = 0xFFD7DEE6;

	private final Consumer<PanelButton> onPress;

	public PanelButton(int x, int y, int width, int height, Component message, Consumer<PanelButton> onPress) {
		super(x, y, width, height, message);
		this.onPress = onPress;
	}

	@Override
	public void onPress(InputWithModifiers input) {
		onPress.accept(this);
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		int left = getX();
		int top = getY();
		int right = left + width;
		int bottom = top + height;

		boolean hovered = isHovered;
		int fill = hovered ? FILL_HOVERED : FILL;
		int border = hovered ? BORDER_HOVERED : BORDER;

		graphics.fill(left, top, right, bottom, fill);
		graphics.fill(left, top, right, top + 1, border);
		graphics.fill(left, bottom - 1, right, bottom, border);
		graphics.fill(left, top, left + 1, bottom, border);
		graphics.fill(right - 1, top, right, bottom, border);

		var font = Minecraft.getInstance().font;
		graphics.text(font, getMessage(),
				left + (width - font.width(getMessage())) / 2,
				top + (height - font.lineHeight) / 2,
				TEXT);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		defaultButtonNarrationText(output);
	}
}
