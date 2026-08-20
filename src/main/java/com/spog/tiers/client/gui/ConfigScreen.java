package com.spog.tiers.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * SpogTiers settings, styled to match the tier viewer: a dimmed backdrop and a
 * single bordered panel.
 *
 * <p>Deliberately empty for now -- it exists so ModMenu has somewhere to send
 * people, and so options have a home to land in.
 */
public class ConfigScreen extends Screen {
	private static final int PANEL_WIDTH = 320;
	private static final int PANEL_HEIGHT = 200;
	private static final int CARD_PADDING = 10;
	private static final int MUTED_COLOR = 0xFF6C7683;
	private static final int CARD_FILL = 0x50161B22;
	private static final int CARD_BORDER = 0x70323B47;

	private final Screen parent;

	public ConfigScreen(Screen parent) {
		super(Component.literal("SpogTiers"));
		this.parent = parent;
	}

	private int panelLeft() {
		return (width - PANEL_WIDTH) / 2;
	}

	private int panelTop() {
		return (height - PANEL_HEIGHT) / 2;
	}

	@Override
	protected void init() {
		int buttonWidth = 100;
		addRenderableWidget(new PanelButton(
				panelLeft() + (PANEL_WIDTH - buttonWidth) / 2,
				panelTop() + PANEL_HEIGHT - CARD_PADDING - 20,
				buttonWidth,
				20,
				Component.literal("Done"),
				button -> onClose()));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		// NB: the blurred background is drawn for us by the framework, which
		// calls extractBackground immediately before this method. Blurring again
		// here throws "Can only blur once per frame".
		graphics.fill(0, 0, width, height, 0xC00B0E13);

		Font font = this.font;
		int left = panelLeft();
		int top = panelTop();
		int right = left + PANEL_WIDTH;
		int bottom = top + PANEL_HEIGHT;

		graphics.fill(left, top, right, bottom, CARD_FILL);
		graphics.fill(left, top, right, top + 1, CARD_BORDER);
		graphics.fill(left, bottom - 1, right, bottom, CARD_BORDER);
		graphics.fill(left, top, left + 1, bottom, CARD_BORDER);
		graphics.fill(right - 1, top, right, bottom, CARD_BORDER);

		String title = "SpogTiers";
		graphics.text(font, Component.literal(title),
				left + (PANEL_WIDTH - font.width(title)) / 2, top + CARD_PADDING, 0xFFFFFFFF);

		int ruleY = top + CARD_PADDING + font.lineHeight + 5;
		graphics.fill(left + CARD_PADDING, ruleY, right - CARD_PADDING, ruleY + 1, 0x28FFFFFF);

		String empty = "No settings yet";
		graphics.text(font, Component.literal(empty),
				left + (PANEL_WIDTH - font.width(empty)) / 2, ruleY + 20, MUTED_COLOR);

		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
