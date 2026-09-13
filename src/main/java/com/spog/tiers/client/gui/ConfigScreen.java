package com.spog.tiers.client.gui;

import com.spog.tiers.SpogTiers;
import com.spog.tiers.SpogTiersClient;
import com.spog.tiers.compat.NametagTweaks;
import com.spog.tiers.config.SpogTiersConfig;
import com.spog.tiers.data.Gamemode;
import com.spog.tiers.data.Regions;
import com.spog.tiers.data.Tier;
import com.spog.tiers.data.TierList;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Full-screen settings, styled to match the tier viewer: a dimmed backdrop, a
 * row of tabs and one bordered, scrollable panel per tab.
 */
public class ConfigScreen extends Screen {
	private static final int MARGIN = 10;
	private static final int CARD_PADDING = 12;
	private static final int ROW_HEIGHT = 22;
	private static final int TAB_HEIGHT = 22;
	private static final int LOGO_SIZE = 14;
	private static final int LABEL_COLOR = 0xFFB9C4D0;
	private static final int MUTED_COLOR = 0xFF6C7683;
	private static final int CARD_FILL = 0x50161B22;
	private static final int CARD_BORDER = 0x70323B47;
	private static final int ACCENT = 0xFF6FC3E8;

	/** The sliding switch shared with the nametag editor. */
	private static final int SLIDER_WIDTH = 22;
	private static final int SLIDER_ON = 0xFF4CAF50;
	private static final int SLIDER_OFF = 0xFFC1443C;

	private enum Tab {
		GENERAL("General"),
		TIER_LISTS("Tierlists"),
		NAMETAG("Nametag"),
		INTEGRATIONS("Integrations");

		private final String title;

		Tab(String title) {
			this.title = title;
		}
	}

	private final Screen parent;
	private Tab active = Tab.GENERAL;

	/** Hidden on the nametag tab, which has its own session controls. */
	private PanelButton doneButton;

	/** The width Done takes, which its replacements match. */
	private static final int DONE_WIDTH = 90;

	/** Click targets rebuilt every frame, so painting and hit-testing agree. */
	private final List<Zone> zones = new ArrayList<>();

	private int scroll;
	private int contentHeight;
	/** Largest valid scroll offset, refreshed each frame for the input handler. */
	private int maxScroll;
	/** The label under the cursor this frame, if it has an explanation. */
	private HoverLabel hoverLabel;

	/**
	 * Where the cursor is this frame.
	 *
	 * <p>Kept so a row can tell whether the cursor is over it while the panel
	 * is being laid out, which is what lets two explained rows sitting next to
	 * each other each keep their own line.
	 */
	private int hoverMouseX;
	private int hoverMouseY;

	private Dropdown<SpogTiersConfig.SortOrder> sortOrder;

	/** The nametag tab, which is its own editor rather than a list of rows. */
	private final TagEditor tagEditor = new TagEditor(
			net.minecraft.client.Minecraft.getInstance().font, null);

	{
		tagEditor.onClose(this::onClose);
	}

	public ConfigScreen(Screen parent) {
		super(Component.literal("SpogTiers"));
		this.parent = parent;
	}

	private SpogTiersConfig config() {
		return SpogTiersClient.config();
	}

	@Override
	protected void init() {
		SpogTiersConfig config = config();

		sortOrder = new Dropdown<>(value -> {
			config.sortOrder = value;
			config.save();
			click();
		});

		// The nametag tab ends a session rather than just closing a screen, so
		// it puts its own Save & Close, Cancel and Reset here instead. Done is
		// only added for the tabs that have nothing to commit.
		doneButton = new PanelButton(
				width - MARGIN - CARD_PADDING - DONE_WIDTH,
				height - MARGIN - CARD_PADDING - 14,
				DONE_WIDTH, 20,
				Component.literal("Done"),
				button -> onClose());
		addRenderableWidget(doneButton);
		doneButton.visible = active != Tab.NAMETAG;
	}

	/** Vanilla's UI click, so the panel feels like the rest of the game. */
	private void click() {
		Minecraft.getInstance().getSoundManager()
				.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		// NB: the blurred background is drawn for us by the framework, which
		// calls extractBackground immediately before this method.
		graphics.fill(0, 0, width, height, 0xC00B0E13);
		zones.clear();
		hoverLabel = null;
		hoverMouseX = mouseX;
		hoverMouseY = mouseY;

		int left = MARGIN;
		int top = MARGIN;
		int right = width - MARGIN;
		int bottom = height - MARGIN;

		drawFrame(graphics, left, top, right, top + TAB_HEIGHT + CARD_PADDING);
		drawTabs(graphics, left, top, mouseX, mouseY);

		int bodyTop = top + TAB_HEIGHT + CARD_PADDING + 6;
		// The nametag tab draws its own three panels edge to edge, so the body
		// frame would only add a line across their tops joining them together.
		if (active != Tab.NAMETAG) {
			drawFrame(graphics, left, bodyTop, right, bottom);
		}

		// Scroll the body, then clamp so a short page cannot drift off.
		int viewHeight = bottom - bodyTop - 40;
		int originY = bodyTop - scroll;

		graphics.enableScissor(left + 1, bodyTop + 1, right - 1, bodyTop + viewHeight);
		switch (active) {
			case GENERAL -> contentHeight =
					drawGeneral(graphics, left, originY, mouseX, mouseY);
			case TIER_LISTS -> contentHeight =
					drawTierLists(graphics, left, originY, right, mouseX, mouseY);
			case INTEGRATIONS -> contentHeight =
					drawIntegrations(graphics, left, originY, mouseX, mouseY);
			// Not scrolled with the others: the editor is three fixed panels
			// that fill the body, and its right panel scrolls on its own.
			case NAMETAG -> contentHeight = 0;
		}
		graphics.disableScissor();
		if (active == Tab.NAMETAG) {
			tagEditor.draw(graphics, left, bodyTop, right,
					bodyTop + viewHeight, mouseX, mouseY);
			// Lined up with the right panel's edge rather than the screen's
			// margin, so the row of buttons ends where the panels above it do.
			tagEditor.drawActions(graphics,
					right - DONE_WIDTH,
					height - MARGIN - CARD_PADDING - 14,
					DONE_WIDTH, mouseX, mouseY);
		}
		// Clamping here as well as on input keeps a resize or a tab switch from
		// leaving the view scrolled past the end.
		maxScroll = Math.max(0, contentHeight - viewHeight);
		scroll = Math.clamp(scroll, 0, maxScroll);

		if (contentHeight > viewHeight) {
			drawScrollbar(graphics, right - 5, bodyTop + 4, viewHeight, contentHeight);
		}

		// Outside the scissor, so it is never clipped to the body.
		if (hoverLabel != null && hoverLabel.contains(mouseX, mouseY)) {
			drawHoverText(graphics, hoverLabel.text(), mouseX, mouseY);
		}
		if (active == Tab.NAMETAG && tagEditor.hoverText() != null) {
			drawHoverText(graphics, tagEditor.hoverText(), mouseX, mouseY);
		}
		if (active == Tab.NAMETAG) {
			tagEditor.drawButtonTooltip(graphics, mouseX, mouseY, width, height);
		}

		super.extractRenderState(graphics, mouseX, mouseY, partialTick);

		// Our controls are drawn rather than being widgets, so vanilla's own
		// hover cursor never applies to them. Requested after the widgets so a
		// control under the pointer wins over the panel behind it.
		requestPointerCursor(graphics, mouseX, mouseY);

		// Open dropdowns paint last so they overlap the rows beneath them.
		if (active == Tab.NAMETAG) {
			tagEditor.drawOverlays(graphics, mouseX, mouseY);
		} else if (active == Tab.GENERAL) {
			sortOrder.drawOverlay(graphics, font, config().sortOrder, mouseX, mouseY);
		}
	}

	/**
	 * Shows the pointing hand over anything clickable.
	 *
	 * <p>Zones cover the tabs, switches and rows; the dropdowns are asked
	 * separately since their open list is not a zone.
	 */
	private void requestPointerCursor(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		// A held element first: while one is on the pointer it does not
		// matter what the pointer happens to be over, the hand stays shut
		// until it is let go.
		if (active == Tab.NAMETAG && tagEditor.isHoldingElement()) {
			graphics.requestCursor(hand(HandCursors.closed()));
			return;
		}
		// A text box next: it sits inside the same panel the rows do, so
		// asking for the hand first would win and the box would never show
		// the caret that says it can be typed in.
		if (active == Tab.NAMETAG && tagEditor.isOverText(mouseX, mouseY)) {
			graphics.requestCursor(CursorTypes.IBEAM);
			return;
		}
		// An element that can be picked up, before the buttons: an element is
		// dragged where a button is pressed, and the cursor is what says so
		// before anyone tries it.
		//
		// The pointing hand rather than an open one. GLFW has no open-hand
		// shape and Windows ships no such cursor to copy -- its only hand is
		// this one -- so a drawn stand-in was never going to match the system
		// it sat beside. The closed hand stays hand-drawn because there is no
		// stock cursor for "holding something" at all.
		if (active == Tab.NAMETAG && tagEditor.isOverElement(mouseX, mouseY)) {
			graphics.requestCursor(CursorTypes.POINTING_HAND);
			return;
		}
		boolean over = false;
		for (Dropdown<?> dropdown : dropdowns()) {
			if (dropdown != null && dropdown.isHovered(font, mouseX, mouseY)) {
				over = true;
				break;
			}
		}
		if (!over && active == Tab.NAMETAG && tagEditor.isOverControl(mouseX, mouseY)) {
			over = true;
		}
		if (!over) {
			for (Zone zone : zones) {
				if (zone.contains(mouseX, mouseY)) {
					over = true;
					break;
				}
			}
		}
		if (over) {
			graphics.requestCursor(CursorTypes.POINTING_HAND);
		}
	}

	/**
	 * One of our own hands, or the pointing hand when it could not be built.
	 *
	 * <p>GLFW has no open or grabbing hand of its own, so ours are made from
	 * images and can fail on a driver that refuses them. The pointing hand at
	 * least still says the thing under it can be used.
	 */
	private static com.mojang.blaze3d.platform.cursor.CursorType hand(
			com.mojang.blaze3d.platform.cursor.CursorType cursor) {
		return cursor != null ? cursor : CursorTypes.POINTING_HAND;
	}

	/** Every dropdown on the active tab. */
	private List<Dropdown<?>> dropdowns() {
		if (active == Tab.NAMETAG) {
			return tagEditor.dropdowns();
		}
		return active == Tab.GENERAL ? List.of(sortOrder) : List.of();
	}

	private void drawScrollbar(GuiGraphicsExtractor graphics, int x, int top, int viewHeight, int total) {
		int thumbHeight = Math.max(16, viewHeight * viewHeight / total);
		int thumbY = top + (viewHeight - thumbHeight) * scroll / Math.max(1, total - viewHeight);
		graphics.fill(x, top, x + 3, top + viewHeight, 0x40202A38);
		graphics.fill(x, thumbY, x + 3, thumbY + thumbHeight, 0x90727F8F);
	}

	private void drawFrame(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom) {
		graphics.fill(left, top, right, bottom, CARD_FILL);
		graphics.fill(left, top, right, top + 1, CARD_BORDER);
		graphics.fill(left, bottom - 1, right, bottom, CARD_BORDER);
		graphics.fill(left, top, left + 1, bottom, CARD_BORDER);
		graphics.fill(right - 1, top, right, bottom, CARD_BORDER);
	}

	private void drawTabs(GuiGraphicsExtractor graphics, int left, int top, int mouseX, int mouseY) {
		int x = left + CARD_PADDING;
		int y = top + CARD_PADDING / 2;

		for (Tab tab : Tab.values()) {
			int tabWidth = font.width(tab.title) + 24;
			boolean selected = tab == active;
			boolean hovered = mouseX >= x && mouseX <= x + tabWidth
					&& mouseY >= y && mouseY <= y + TAB_HEIGHT;

			if (selected) {
				graphics.fill(x, y, x + tabWidth, y + TAB_HEIGHT, 0x6022303F);
				graphics.fill(x, y + TAB_HEIGHT - 2, x + tabWidth, y + TAB_HEIGHT, ACCENT);
			} else if (hovered) {
				graphics.fill(x, y, x + tabWidth, y + TAB_HEIGHT, 0x3016202B);
			}

			graphics.text(font, Component.literal(tab.title),
					x + (tabWidth - font.width(tab.title)) / 2,
					y + (TAB_HEIGHT - font.lineHeight) / 2,
					selected ? 0xFFFFFFFF : (hovered ? LABEL_COLOR : MUTED_COLOR));

			zones.add(new Zone(x, y, x + tabWidth, y + TAB_HEIGHT, () -> {
				// Clicking the tab already open changes nothing, so it should
				// not sound as though it did.
				if (active == tab) {
					return;
				}
				active = tab;
				scroll = 0;
				closeDropdowns();
				if (tab == Tab.NAMETAG) {
					// A fresh working copy each time the tab is entered, so
					// Cancel undoes this visit rather than every visit since
					// the screen was opened.
					tagEditor.open();
				}
				if (doneButton != null) {
					doneButton.visible = tab != Tab.NAMETAG;
				}
				click();
			}));
			x += tabWidth + 4;
		}
	}

	/**
	 * Settings that only matter alongside another mod.
	 *
	 * <p>A section per mod, and each says plainly when the mod it is about is
	 * not installed rather than offering a switch that would do nothing.
	 */
	private int drawIntegrations(GuiGraphicsExtractor graphics, int left, int top,
			int mouseX, int mouseY) {
		SpogTiersConfig config = config();
		int x = left + CARD_PADDING;
		int y = top + CARD_PADDING;

		graphics.text(font, Component.literal("Nametag Tweaks"), x, y, LABEL_COLOR);
		y += font.lineHeight + 8;

		if (!NametagTweaks.present()) {
			graphics.text(font, Component.literal("Not installed"), x, y, MUTED_COLOR);
			return y + ROW_HEIGHT + CARD_PADDING - top;
		}

		y = drawSwitch(graphics, "Visual Adjustments", config.matchNametagTweaks,
				x, y, () -> {
					config.matchNametagTweaks = !config.matchNametagTweaks;
					config.save();
				},
				"If the size, height and color adjustments to the nametag "
						+ "should affect the other lines");

		return y + CARD_PADDING - top;
	}

	private int drawGeneral(GuiGraphicsExtractor graphics, int left, int top,
			int mouseX, int mouseY) {
		SpogTiersConfig config = config();
		int x = left + CARD_PADDING;
		int y = top + CARD_PADDING;

		graphics.text(font, Component.literal("General"), x, y, 0xFFFFFFFF);
		y += font.lineHeight + 10;

		graphics.text(font, Component.literal("Sorting"), x, y, LABEL_COLOR);

		List<Dropdown.Entry<SpogTiersConfig.SortOrder>> orders = new ArrayList<>();
		for (SpogTiersConfig.SortOrder value : SpogTiersConfig.SortOrder.values()) {
			orders.add(new Dropdown.Entry<>(value, value.title(), null));
		}
		sortOrder.setEntries(orders);
		sortOrder.setBounds(x + 118, y - 4, 152);
		sortOrder.draw(graphics, font, config.sortOrder, mouseX, mouseY);

		y += ROW_HEIGHT + 8;

		y = drawSwitch(graphics, "Show HQ Placements", config.showPlacements, x, y,
				() -> {
					config.showPlacements = !config.showPlacements;
					config.save();
				},
				"If placement rounds should be shown in the tier viewer");

		y = drawSwitch(graphics, "Show Peak Tiers", config.showPeakTiers, x, y,
				() -> {
					config.showPeakTiers = !config.showPeakTiers;
					config.save();
				},
				"Show the peak tier a player has held");

		y = drawSwitch(graphics, "Show Retired", config.showRetired, x, y,
				() -> {
					config.showRetired = !config.showRetired;
					config.save();
				},
				"Show retired ranks as retired, rather than as the plain tier");

		y = drawSwitch(graphics, "Show Discord", config.showDiscord, x, y,
				() -> {
					config.showDiscord = !config.showDiscord;
					config.save();
				},
				"Show a player's linked Discord account on their profile");

		y = drawSwitch(graphics, "Show Particles", config.showParticles, x, y,
				() -> {
					config.showParticles = !config.showParticles;
					config.save();
				});

		return y + CARD_PADDING - top;
	}

	/** Per-list visibility. */
	private int drawTierLists(GuiGraphicsExtractor graphics, int left, int top, int right,
			int mouseX, int mouseY) {
		SpogTiersConfig config = config();
		int x = left + CARD_PADDING;
		int y = top + CARD_PADDING;

		graphics.text(font, Component.literal("Tierlists"), x, y, 0xFFFFFFFF);
		y += font.lineHeight + 10;

		for (TierList list : TierList.values()) {
			boolean shown = config.isEnabled(list);

			int rowTop = y - 4;
			int rowBottom = y + ROW_HEIGHT - 8;
			if (mouseY >= rowTop && mouseY <= rowBottom && mouseX >= x
					&& mouseX <= right - CARD_PADDING) {
				graphics.fill(x - 4, rowTop, right - CARD_PADDING, rowBottom, 0x8022303F);
			}

			graphics.blit(RenderPipelines.GUI_TEXTURED, logoOf(list), x, y - 4, 0.0f, 0.0f,
					LOGO_SIZE, LOGO_SIZE, 64, 64, 64, 64);
			graphics.text(font, Component.literal(list.displayName()),
					x + LOGO_SIZE + 6, y, shown ? 0xFFFFFFFF : MUTED_COLOR);

			// Two toggles: whether the list is looked up at all, and whether it
			// may be quoted on a nametag. Hiding a list disables the tag
			// toggle with it, since a list nobody queries has nothing to tag.
			boolean tagged = config.isTagged(list);

			int toggleWidth = 92;
			// The height drawToggle actually draws. Measured rather than
			// written out again: a zone a pixel short of its box left the
			// bottom row of each switch dead to both hover and click.
			int toggleHeight = font.lineHeight + 8;
			int resultsX = right - CARD_PADDING - toggleWidth;
			int tagsX = resultsX - toggleWidth - 6;

			drawToggle(graphics, tagsX, y - 4, toggleWidth,
					tagged ? "SHOWN TAGS" : "HIDDEN TAGS", tagged, shown);
			if (shown) {
				zones.add(new Zone(tagsX, y - 4, tagsX + toggleWidth,
						y - 4 + toggleHeight, () -> {
					config.setTagged(list, !tagged);
					config.save();
					click();
				}));
			}

			drawToggle(graphics, resultsX, y - 4, toggleWidth,
					shown ? "SHOWN RESULTS" : "HIDDEN RESULTS", shown, true);
			zones.add(new Zone(resultsX, y - 4, resultsX + toggleWidth,
					y - 4 + toggleHeight, () -> {
				config.setEnabled(list, !shown);
				config.save();
				click();
			}));

			y += ROW_HEIGHT;
		}
		return y + CARD_PADDING - top;
	}


	/** A sample nametag built from the current settings. */
	/**
	 * The player the preview is built around.
	 *
	 * <p>A real account rather than a made-up one, so the preview shows the
	 * tags he actually holds once his tiers are in the cache. Nothing is
	 * fetched from here: this runs on the render thread, so it reads whatever
	 * the cache already has and falls back to a sample tier otherwise.
	 */
	private static final UUID PREVIEW_PLAYER =
			UUID.fromString("ebd7af32-759e-41e2-b227-9eeb8576d609");

	private static final String PREVIEW_NAME = "Swight";





	private int drawSwitch(GuiGraphicsExtractor graphics, String title, boolean on,
			int x, int y, Runnable onClick) {
		return drawSwitch(graphics, title, on, x, y, onClick, null);
	}

	/**
	 * A labelled on/off row, with an optional line explaining it on hover.
	 *
	 * <p>The label is what carries the explanation, not the toggle: the toggle
	 * is what you click, and a tooltip appearing under the cursor as you go to
	 * press it is in the way.
	 */
	private int drawSwitch(GuiGraphicsExtractor graphics, String title, boolean on,
			int x, int y, Runnable onClick, String description) {
		graphics.text(font, Component.literal(title), x, y, LABEL_COLOR);
		// The nametag editor's sliding switch rather than a lettered box, so
		// the two tabs read the same way.
		int toggleX = x + 118;
		drawSlider(graphics, toggleX, y - 2, on);
		zones.add(new Zone(toggleX, y - 4, toggleX + SLIDER_WIDTH, y + 12, () -> {
			onClick.run();
			click();
		}));
		if (description != null) {
			// Recorded rather than drawn here: the body is scissored, so a
			// tooltip drawn now would be clipped to the panel.
			//
			// Kept only when nothing else has already claimed the cursor, so
			// that two explained rows next to each other each show their own
			// line rather than the later one overwriting the earlier.
			HoverLabel candidate =
					new HoverLabel(x, y - 2, x + font.width(title), y + 10, description);
			if (hoverLabel == null || candidate.contains(hoverMouseX, hoverMouseY)) {
				hoverLabel = candidate;
			}
		}
		return y + ROW_HEIGHT;
	}

	/**
	 * A sliding switch: green when on, red when off, with the knob at the
	 * end it is set to. The same control the nametag editor draws.
	 */
	private void drawSlider(GuiGraphicsExtractor graphics, int x, int y, boolean on) {
		int height = 12;
		graphics.fill(x, y, x + SLIDER_WIDTH, y + height, on ? SLIDER_ON : SLIDER_OFF);
		int knob = on ? x + SLIDER_WIDTH - 10 : x + 2;
		graphics.fill(knob, y + 2, knob + 8, y + height - 2, 0xFFFFFFFF);
	}

	private void drawToggle(GuiGraphicsExtractor graphics, int x, int y, int boxWidth,
			String text, boolean on) {
		drawToggle(graphics, x, y, boxWidth, text, on, true);
	}

	/**
	 * A boxed on/off label.
	 *
	 * <p>{@code enabled} is separate from {@code on}: a toggle that cannot be
	 * changed right now is drawn faded rather than hidden, so the row keeps its
	 * shape and the reason stays visible.
	 */
	private void drawToggle(GuiGraphicsExtractor graphics, int x, int y, int boxWidth,
			String text, boolean on, boolean enabled) {
		int boxHeight = font.lineHeight + 8;
		int fill;
		int border;
		int textColor;
		if (!enabled) {
			fill = 0x30161B22;
			border = 0x50323B47;
			textColor = 0xFF5A636E;
		} else {
			fill = on ? 0x5023351F : 0x50241A1D;
			border = on ? 0xA05F9A56 : 0xA0955A5A;
			textColor = on ? 0xFFA8E39B : 0xFFE0A0A0;
		}

		graphics.fill(x, y, x + boxWidth, y + boxHeight, fill);
		graphics.fill(x, y, x + boxWidth, y + 1, border);
		graphics.fill(x, y + boxHeight - 1, x + boxWidth, y + boxHeight, border);
		graphics.fill(x, y, x + 1, y + boxHeight, border);
		graphics.fill(x + boxWidth - 1, y, x + boxWidth, y + boxHeight, border);

		graphics.text(font, Component.literal(text),
				x + (boxWidth - font.width(text)) / 2, y + 4, textColor);
	}

	static Identifier logoOf(TierList list) {
		return Identifier.fromNamespaceAndPath(SpogTiers.MOD_ID, list.logoPath());
	}

	/**
	 * A list's artwork for a gamemode, or null when it does not rank it.
	 *
	 * <p>Each site only ships icons for its own modes, so asking PVPHQ for
	 * Vanilla resolves to a texture that does not exist and renders as the
	 * missing-texture chequer.
	 */
	static Identifier modeIcon(TierList list, Gamemode mode) {
		if (list == null || mode == null || !list.gamemodes().contains(mode)) {
			return null;
		}
		return Identifier.fromNamespaceAndPath(SpogTiers.MOD_ID, list.modeIconPath(mode.key()));
	}

	/** Any enabled list, for a preview that is not bound to one. */
	private TierList firstEnabledList() {
		SpogTiersConfig config = config();
		for (TierList list : TierList.values()) {
			if (config.isEnabled(list)) {
				return list;
			}
		}
		return null;
	}

	/** An enabled list that ranks this mode, for borrowing its icon. */
	private TierList firstListWith(Gamemode mode) {
		SpogTiersConfig config = config();
		for (TierList list : TierList.values()) {
			if (config.isEnabled(list) && list.gamemodes().contains(mode)) {
				return list;
			}
		}
		return null;
	}

	private void closeDropdowns() {
		sortOrder.close();
		tagEditor.closeDropdowns();
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubled) {
		// Dropdowns first: an open list sits above everything else.
		if (active == Tab.GENERAL && sortOrder.click(font, event.x(), event.y())) {
			click();
			return true;
		}
		if (active == Tab.NAMETAG) {
			for (Dropdown<?> dropdown : dropdowns()) {
				if (dropdown.isOpen() && dropdown.click(font, event.x(), event.y())) {
					return true;
				}
			}
			for (Dropdown<?> dropdown : dropdowns()) {
				if (dropdown.click(font, event.x(), event.y())) {
					click();
					return true;
				}
			}
			if (tagEditor.click(event, doubled)) {
				click();
				return true;
			}
		}

		for (Zone zone : zones) {
			if (zone.contains(event.x(), event.y())) {
				zone.action().run();
				return true;
			}
		}
		return super.mouseClicked(event, doubled);
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		if (active == Tab.NAMETAG && tagEditor.keyPressed(event)) {
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
		if (active == Tab.NAMETAG && tagEditor.charTyped(event)) {
			return true;
		}
		return super.charTyped(event);
	}

	@Override
	public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event,
			double dragX, double dragY) {
		if (active == Tab.NAMETAG) {
			tagEditor.drag(event, dragX, dragY);
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
		if (active == Tab.NAMETAG && tagEditor.release(event.x(), event.y())) {
			click();
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
		if (active == Tab.GENERAL && sortOrder.scroll(deltaY)) {
			return true;
		}
		if (active == Tab.NAMETAG) {
			for (Dropdown<?> dropdown : dropdowns()) {
				if (dropdown.scroll(deltaY)) {
					return true;
				}
			}
			if (tagEditor.scroll(deltaY)) {
				return true;
			}
		}
		scroll = Math.clamp(scroll - (int) (deltaY * 12), 0, maxScroll);
		return true;
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	/** A label with an explanation, and the box that triggers it. */
	private record HoverLabel(int left, int top, int right, int bottom, String text) {
		boolean contains(int mouseX, int mouseY) {
			return mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= bottom;
		}
	}

	/**
	 * The mod's tooltip: a bordered card in the same palette as the panels,
	 * kept inside the window.
	 */
	private void drawHoverText(GuiGraphicsExtractor graphics, String text,
			int mouseX, int mouseY) {
		int boxWidth = font.width(text) + 12;
		int boxHeight = font.lineHeight + 12;
		int boxX = Tooltips.x(mouseX, boxWidth, width);
		int boxY = Math.clamp(mouseY - 8, 4, height - boxHeight - 4);

		graphics.fill(boxX, boxY, boxX + boxWidth, boxY + boxHeight, CARD_BORDER);
		graphics.fill(boxX + 1, boxY + 1, boxX + boxWidth - 1, boxY + boxHeight - 1, 0xE00E1219);
		graphics.text(font, Component.literal(text), boxX + 6, boxY + 6, LABEL_COLOR);
	}

	private record Zone(int left, int top, int right, int bottom, Runnable action) {
		boolean contains(double x, double y) {
			return x >= left && x <= right && y >= top && y <= bottom;
		}
	}
}
