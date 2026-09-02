package com.spog.tiers.client.gui;

import com.spog.tiers.SpogTiers;
import com.spog.tiers.SpogTiersClient;
import com.spog.tiers.config.SpogTiersConfig;
import com.spog.tiers.data.Gamemode;
import com.spog.tiers.data.Tier;
import com.spog.tiers.data.TierList;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.cursor.StandardCursors;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.sound.SoundEvents;

import java.util.ArrayList;
import java.util.List;

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

	private enum Tab {
		GENERAL("General"),
		TIER_LISTS("Tierlists"),
		NAMETAG("Nametag");

		private final String title;

		Tab(String title) {
			this.title = title;
		}
	}

	private final Screen parent;
	private Tab active = Tab.GENERAL;

	/** Click targets rebuilt every frame, so painting and hit-testing agree. */
	private final List<Zone> zones = new ArrayList<>();

	private int scroll;
	private int contentHeight;
	/** Largest valid scroll offset, refreshed each frame for the input handler. */
	private int maxScroll;

	private Dropdown<TierList> leftList;
	private Dropdown<Gamemode> leftMode;
	private Dropdown<TierList> rightList;
	private Dropdown<Gamemode> rightMode;
	private Dropdown<SpogTiersConfig.SortOrder> sortOrder;

	public ConfigScreen(Screen parent) {
		super(Text.literal("SpogTiers"));
		this.parent = parent;
	}

	private SpogTiersConfig config() {
		return SpogTiersClient.config();
	}

	@Override
	protected void init() {
		SpogTiersConfig config = config();

		leftList = new Dropdown<>(value -> {
			config.leftTag.list = value;
			config.leftTag.gamemode = null;
			config.save();
			click();
		});
		leftMode = new Dropdown<>(value -> {
			config.leftTag.gamemode = value;
			config.save();
			click();
		});
		rightList = new Dropdown<>(value -> {
			config.rightTag.list = value;
			config.rightTag.gamemode = null;
			config.save();
			click();
		});
		rightMode = new Dropdown<>(value -> {
			config.rightTag.gamemode = value;
			config.save();
			click();
		});
		sortOrder = new Dropdown<>(value -> {
			config.sortOrder = value;
			config.save();
			click();
		});

		addDrawableChild(new PanelButton(
				width - MARGIN - CARD_PADDING - 90,
				height - MARGIN - CARD_PADDING - 14,
				90, 20,
				Text.literal("Done"),
				button -> close()));
	}

	/** Vanilla's UI click, so the panel feels like the rest of the game. */
	private void click() {
		MinecraftClient.getInstance().getSoundManager()
				.play(PositionedSoundInstance.ui(SoundEvents.UI_BUTTON_CLICK, 1.0f));
	}

	@Override
	public void render(DrawContext graphics, int mouseX, int mouseY, float partialTick) {
		// NB: the blurred background is drawn for us by the framework, which
		// calls extractBackground immediately before this method.
		graphics.fill(0, 0, width, height, 0xC00B0E13);
		zones.clear();

		int left = MARGIN;
		int top = MARGIN;
		int right = width - MARGIN;
		int bottom = height - MARGIN;

		drawFrame(graphics, left, top, right, top + TAB_HEIGHT + CARD_PADDING);
		drawTabs(graphics, left, top, mouseX, mouseY);

		int bodyTop = top + TAB_HEIGHT + CARD_PADDING + 6;
		drawFrame(graphics, left, bodyTop, right, bottom);

		// Scroll the body, then clamp so a short page cannot drift off.
		int viewHeight = bottom - bodyTop - 40;
		int originY = bodyTop - scroll;

		graphics.enableScissor(left + 1, bodyTop + 1, right - 1, bodyTop + viewHeight);
		switch (active) {
			case GENERAL -> contentHeight =
					drawGeneral(graphics, left, originY, mouseX, mouseY);
			case TIER_LISTS -> contentHeight =
					drawTierLists(graphics, left, originY, right, mouseX, mouseY);
			case NAMETAG -> contentHeight =
					drawNametag(graphics, left, originY, right, mouseX, mouseY);
		}
		graphics.disableScissor();
		// Clamping here as well as on input keeps a resize or a tab switch from
		// leaving the view scrolled past the end.
		maxScroll = Math.max(0, contentHeight - viewHeight);
		scroll = Math.clamp(scroll, 0, maxScroll);

		if (contentHeight > viewHeight) {
			drawScrollbar(graphics, right - 5, bodyTop + 4, viewHeight, contentHeight);
		}

		super.render(graphics, mouseX, mouseY, partialTick);

		// Our controls are drawn rather than being widgets, so vanilla's own
		// hover cursor never applies to them. Requested after the widgets so a
		// control under the pointer wins over the panel behind it.
		requestPointerCursor(graphics, mouseX, mouseY);

		// Open dropdowns paint last so they overlap the rows beneath them.
		if (active == Tab.NAMETAG) {
			SpogTiersConfig config = config();
			leftList.drawOverlay(graphics, textRenderer, config.leftTag.list, mouseX, mouseY);
			leftMode.drawOverlay(graphics, textRenderer, config.leftTag.gamemode, mouseX, mouseY);
			rightList.drawOverlay(graphics, textRenderer, config.rightTag.list, mouseX, mouseY);
			rightMode.drawOverlay(graphics, textRenderer, config.rightTag.gamemode, mouseX, mouseY);
		} else if (active == Tab.GENERAL) {
			sortOrder.drawOverlay(graphics, textRenderer, config().sortOrder, mouseX, mouseY);
		}
	}

	/**
	 * Shows the pointing hand over anything clickable.
	 *
	 * <p>Zones cover the tabs, switches and rows; the dropdowns are asked
	 * separately since their open list is not a zone.
	 */
	private void requestPointerCursor(DrawContext graphics, int mouseX, int mouseY) {
		boolean over = false;
		for (Dropdown<?> dropdown : dropdowns()) {
			if (dropdown != null && dropdown.isHovered(textRenderer, mouseX, mouseY)) {
				over = true;
				break;
			}
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
			graphics.setCursor(StandardCursors.POINTING_HAND);
		}
	}

	/** Every dropdown on the active tab. */
	private List<Dropdown<?>> dropdowns() {
		if (active == Tab.NAMETAG) {
			return List.of(leftList, leftMode, rightList, rightMode);
		}
		return active == Tab.GENERAL ? List.of(sortOrder) : List.of();
	}

	private void drawScrollbar(DrawContext graphics, int x, int top, int viewHeight, int total) {
		int thumbHeight = Math.max(16, viewHeight * viewHeight / total);
		int thumbY = top + (viewHeight - thumbHeight) * scroll / Math.max(1, total - viewHeight);
		graphics.fill(x, top, x + 3, top + viewHeight, 0x40202A38);
		graphics.fill(x, thumbY, x + 3, thumbY + thumbHeight, 0x90727F8F);
	}

	private void drawFrame(DrawContext graphics, int left, int top, int right, int bottom) {
		graphics.fill(left, top, right, bottom, CARD_FILL);
		graphics.fill(left, top, right, top + 1, CARD_BORDER);
		graphics.fill(left, bottom - 1, right, bottom, CARD_BORDER);
		graphics.fill(left, top, left + 1, bottom, CARD_BORDER);
		graphics.fill(right - 1, top, right, bottom, CARD_BORDER);
	}

	private void drawTabs(DrawContext graphics, int left, int top, int mouseX, int mouseY) {
		int x = left + CARD_PADDING;
		int y = top + CARD_PADDING / 2;

		for (Tab tab : Tab.values()) {
			int tabWidth = textRenderer.getWidth(tab.title) + 24;
			boolean selected = tab == active;
			boolean hovered = mouseX >= x && mouseX <= x + tabWidth
					&& mouseY >= y && mouseY <= y + TAB_HEIGHT;

			if (selected) {
				graphics.fill(x, y, x + tabWidth, y + TAB_HEIGHT, 0x6022303F);
				graphics.fill(x, y + TAB_HEIGHT - 2, x + tabWidth, y + TAB_HEIGHT, ACCENT);
			} else if (hovered) {
				graphics.fill(x, y, x + tabWidth, y + TAB_HEIGHT, 0x3016202B);
			}

			graphics.drawTextWithShadow(textRenderer, Text.literal(tab.title),
					x + (tabWidth - textRenderer.getWidth(tab.title)) / 2,
					y + (TAB_HEIGHT - textRenderer.fontHeight) / 2,
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
				click();
			}));
			x += tabWidth + 4;
		}
	}

	private int drawGeneral(DrawContext graphics, int left, int top,
			int mouseX, int mouseY) {
		SpogTiersConfig config = config();
		int x = left + CARD_PADDING;
		int y = top + CARD_PADDING;

		graphics.drawTextWithShadow(textRenderer, Text.literal("General"), x, y, 0xFFFFFFFF);
		y += textRenderer.fontHeight + 10;

		graphics.drawTextWithShadow(textRenderer, Text.literal("Sorting"), x, y, LABEL_COLOR);

		List<Dropdown.Entry<SpogTiersConfig.SortOrder>> orders = new ArrayList<>();
		for (SpogTiersConfig.SortOrder value : SpogTiersConfig.SortOrder.values()) {
			orders.add(new Dropdown.Entry<>(value, value.title(), null));
		}
		sortOrder.setEntries(orders);
		sortOrder.setBounds(x + 118, y - 4, 152);
		sortOrder.draw(graphics, textRenderer, config.sortOrder, mouseX, mouseY);

		y += ROW_HEIGHT + 8;

		y = drawSwitch(graphics, "Show HQ Placements", config.showPlacements, x, y,
				() -> {
					config.showPlacements = !config.showPlacements;
					config.save();
				});

		y = drawSwitch(graphics, "Extra Tierlists", config.extraTierlists, x, y,
				() -> {
					config.extraTierlists = !config.extraTierlists;
					config.save();
				});

		return y + CARD_PADDING - top;
	}

	/** Per-list visibility. */
	private int drawTierLists(DrawContext graphics, int left, int top, int right,
			int mouseX, int mouseY) {
		SpogTiersConfig config = config();
		int x = left + CARD_PADDING;
		int y = top + CARD_PADDING;

		graphics.drawTextWithShadow(textRenderer, Text.literal("Tierlists"), x, y, 0xFFFFFFFF);
		y += textRenderer.fontHeight + 10;

		for (TierList list : TierList.values()) {
			boolean shown = config.isEnabled(list);

			int rowTop = y - 4;
			int rowBottom = y + ROW_HEIGHT - 8;
			if (mouseY >= rowTop && mouseY <= rowBottom && mouseX >= x
					&& mouseX <= right - CARD_PADDING) {
				graphics.fill(x - 4, rowTop, right - CARD_PADDING, rowBottom, 0x8022303F);
			}

			graphics.drawTexture(RenderPipelines.GUI_TEXTURED, logoOf(list), x, y - 4, 0.0f, 0.0f,
					LOGO_SIZE, LOGO_SIZE, 64, 64, 64, 64);
			graphics.drawTextWithShadow(textRenderer, Text.literal(list.displayName()),
					x + LOGO_SIZE + 6, y, shown ? 0xFFFFFFFF : MUTED_COLOR);

			// Two toggles: whether the list is looked up at all, and whether it
			// may be quoted on a nametag. Hiding a list disables the tag
			// toggle with it, since a list nobody queries has nothing to tag.
			boolean tagged = config.isTagged(list);

			int toggleWidth = 92;
			int resultsX = right - CARD_PADDING - toggleWidth;
			int tagsX = resultsX - toggleWidth - 6;

			drawToggle(graphics, tagsX, y - 4, toggleWidth,
					tagged ? "SHOWN TAGS" : "HIDDEN TAGS", tagged, shown);
			if (shown) {
				zones.add(new Zone(tagsX, y - 4, tagsX + toggleWidth, y + 12, () -> {
					config.setTagged(list, !tagged);
					config.save();
					click();
				}));
			}

			drawToggle(graphics, resultsX, y - 4, toggleWidth,
					shown ? "SHOWN RESULTS" : "HIDDEN RESULTS", shown, true);
			zones.add(new Zone(resultsX, y - 4, resultsX + toggleWidth, y + 12, () -> {
				config.setEnabled(list, !shown);
				config.save();
				click();
			}));

			y += ROW_HEIGHT;
		}
		return y + CARD_PADDING - top;
	}

	/** Tag slots, region prefix, surfaces and a live preview. */
	private int drawNametag(DrawContext graphics, int left, int top, int right,
			int mouseX, int mouseY) {
		SpogTiersConfig config = config();
		int x = left + CARD_PADDING;
		int y = top + CARD_PADDING;

		graphics.drawTextWithShadow(textRenderer, Text.literal("Nametag"), x, y, 0xFFFFFFFF);
		y += textRenderer.fontHeight + 10;

		drawPreview(graphics, x, y, right);
		y += 34;

		y = drawSlot(graphics, "Left", config.leftTag, leftList, leftMode,
				x, y, mouseX, mouseY);
		y = drawSlot(graphics, "Right", config.rightTag, rightList, rightMode,
				x, y, mouseX, mouseY);
		y += 8;

		y = drawSwitch(graphics, "Region", config.showRegionOnNametag, x, y,
				() -> {
					config.showRegionOnNametag = !config.showRegionOnNametag;
					config.save();
				});
		y += textRenderer.fontHeight + 10;

		graphics.drawTextWithShadow(textRenderer, Text.literal("Show tiers in"), x, y, 0xFFFFFFFF);
		y += textRenderer.fontHeight + 8;

		y = drawSwitch(graphics, "Nametag", config.showNametags, x, y,
				() -> {
					config.showNametags = !config.showNametags;
					config.save();
				});
		y = drawSwitch(graphics, "Tab list", config.showTabList, x, y,
				() -> {
					config.showTabList = !config.showTabList;
					config.save();
				});
		y = drawSwitch(graphics, "Chat", config.showInChat, x, y,
				() -> {
					config.showInChat = !config.showInChat;
					config.save();
				});

		return y + textRenderer.fontHeight + CARD_PADDING - top;
	}

	/** A sample nametag built from the current settings. */
	private void drawPreview(DrawContext graphics, int x, int y, int right) {
		SpogTiersConfig config = config();

		int boxWidth = Math.min(380, right - CARD_PADDING - x);
		int boxHeight = 26;
		graphics.fill(x, y, x + boxWidth, y + boxHeight, 0x60101720);
		graphics.fill(x, y, x + boxWidth, y + 1, CARD_BORDER);
		graphics.fill(x, y + boxHeight - 1, x + boxWidth, y + boxHeight, CARD_BORDER);
		graphics.fill(x, y, x + 1, y + boxHeight, CARD_BORDER);
		graphics.fill(x + boxWidth - 1, y, x + boxWidth, y + boxHeight, CARD_BORDER);

		// Sample values, so the preview works with nobody looked up.
		Tier sample = new Tier(1, Tier.Position.HIGH, false);
		int cursor = x + 8;
		int textY = y + (boxHeight - textRenderer.fontHeight) / 2;

		if (config.showRegionOnNametag) {
			graphics.drawTextWithShadow(textRenderer, Text.literal("EU"), cursor, textY, 0xFF89F19C);
			cursor += textRenderer.getWidth("EU") + 5;
		}
		cursor = drawPreviewTag(graphics, config.leftTag, sample, cursor, textY, true);
		graphics.drawTextWithShadow(textRenderer, Text.literal("Notch"), cursor, textY, 0xFFFFFFFF);
		cursor += textRenderer.getWidth("Notch");
		drawPreviewTag(graphics, config.rightTag, sample, cursor, textY, false);
	}

	private int drawPreviewTag(DrawContext graphics, SpogTiersConfig.TagSlot slot,
			Tier sample, int cursor, int textY, boolean before) {
		// A null list is the Best option, which still previews.
		if (slot == null || !slot.enabled) {
			return cursor;
		}

		if (!before) {
			graphics.drawTextWithShadow(textRenderer, Text.literal(" | "), cursor, textY, 0xFF555F6B);
			cursor += textRenderer.getWidth(" | ");
		}

		{
			TierList source = slot.list != null ? slot.list
					: (slot.gamemode != null ? firstListWith(slot.gamemode) : firstEnabledList());
			Gamemode mode = slot.gamemode;
			if (mode == null && source != null) {
				mode = source.gamemodes().stream().findFirst().orElse(null);
			}
			// A list only ships artwork for the modes it ranks, so an unranked
			// pairing (PVPHQ has no Vanilla) would blit a missing texture.
			if (source != null && mode != null && source.gamemodes().contains(mode)) {
				graphics.drawTexture(RenderPipelines.GUI_TEXTURED, modeIcon(source, mode),
						cursor, textY - 1, 0.0f, 0.0f, 10, 10, 64, 64, 64, 64);
				cursor += 13;
			}
		}

		String label = sample.label();
		graphics.drawTextWithShadow(textRenderer, Text.literal(label), cursor, textY, sample.color());
		cursor += textRenderer.getWidth(label);

		if (before) {
			graphics.drawTextWithShadow(textRenderer, Text.literal(" | "), cursor, textY, 0xFF555F6B);
			cursor += textRenderer.getWidth(" | ");
		}
		return cursor;
	}

	private int drawSlot(DrawContext graphics, String title, SpogTiersConfig.TagSlot slot,
			Dropdown<TierList> listDropdown, Dropdown<Gamemode> modeDropdown,
			int x, int y, int mouseX, int mouseY) {
		SpogTiersConfig config = config();
		graphics.drawTextWithShadow(textRenderer, Text.literal(title), x, y, LABEL_COLOR);

		int toggleX = x + 118;
		drawToggle(graphics, toggleX, y - 4, 44, slot.enabled ? "ON" : "OFF", slot.enabled);
		zones.add(new Zone(toggleX, y - 4, toggleX + 44, y + 12, () -> {
			slot.enabled = !slot.enabled;
			config.save();
			click();
		}));

		List<Dropdown.Entry<TierList>> lists = new ArrayList<>();
		// null means "across every list" -- see TagRenderer.
		lists.add(new Dropdown.Entry<>(null, "Best", null));
		for (TierList list : TierList.values()) {
			lists.add(new Dropdown.Entry<>(list, list.displayName(), logoOf(list)));
		}
		listDropdown.setEntries(lists);
		listDropdown.setBounds(toggleX + 50, y - 4, 122);
		listDropdown.draw(graphics, textRenderer, slot.list, mouseX, mouseY);

		// Gamemodes come from the chosen list, so an impossible pairing
		// (PvPTiers + Bed) simply cannot be selected. Under Best there is no one
		// list, so every mode any enabled list ranks is offered, each drawn with
		// the artwork of a list that has it.
		List<Dropdown.Entry<Gamemode>> modes = new ArrayList<>();
		modes.add(new Dropdown.Entry<>(null, "Best tier", null));
		if (slot.list != null) {
			for (Gamemode mode : slot.list.gamemodes()) {
				modes.add(new Dropdown.Entry<>(mode, mode.displayName(), modeIcon(slot.list, mode)));
			}
		} else {
			for (Gamemode mode : Gamemode.values()) {
				TierList owner = firstListWith(mode);
				if (owner != null) {
					modes.add(new Dropdown.Entry<>(mode, mode.displayName(), modeIcon(owner, mode)));
				}
			}
		}
		modeDropdown.setEntries(modes);
		modeDropdown.setBounds(toggleX + 178, y - 4, 128);
		modeDropdown.draw(graphics, textRenderer, slot.gamemode, mouseX, mouseY);

		return y + ROW_HEIGHT;
	}

	private int drawSwitch(DrawContext graphics, String title, boolean on,
			int x, int y, Runnable onClick) {
		graphics.drawTextWithShadow(textRenderer, Text.literal(title), x, y, LABEL_COLOR);
		int toggleX = x + 118;
		drawToggle(graphics, toggleX, y - 4, 44, on ? "ON" : "OFF", on);
		zones.add(new Zone(toggleX, y - 4, toggleX + 44, y + 12, () -> {
			onClick.run();
			click();
		}));
		return y + ROW_HEIGHT;
	}

	private void drawToggle(DrawContext graphics, int x, int y, int boxWidth,
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
	private void drawToggle(DrawContext graphics, int x, int y, int boxWidth,
			String text, boolean on, boolean enabled) {
		int boxHeight = textRenderer.fontHeight + 8;
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

		graphics.drawTextWithShadow(textRenderer, Text.literal(text),
				x + (boxWidth - textRenderer.getWidth(text)) / 2, y + 4, textColor);
	}

	private static Identifier logoOf(TierList list) {
		return Identifier.of(SpogTiers.MOD_ID, list.logoPath());
	}

	/**
	 * A list's artwork for a gamemode, or null when it does not rank it.
	 *
	 * <p>Each site only ships icons for its own modes, so asking PVPHQ for
	 * Vanilla resolves to a texture that does not exist and renders as the
	 * missing-texture chequer.
	 */
	private static Identifier modeIcon(TierList list, Gamemode mode) {
		if (list == null || mode == null || !list.gamemodes().contains(mode)) {
			return null;
		}
		return Identifier.of(SpogTiers.MOD_ID, list.modeIconPath(mode.key()));
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
		if (leftList != null) {
			leftList.close();
			leftMode.close();
			rightList.close();
			rightMode.close();
			sortOrder.close();
		}
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.gui.Click event, boolean doubled) {
		// Dropdowns first: an open list sits above everything else.
		if (active == Tab.GENERAL && sortOrder.click(textRenderer, event.x(), event.y())) {
			click();
			return true;
		}
		if (active == Tab.NAMETAG) {
			for (Dropdown<?> dropdown : List.of(leftList, leftMode, rightList, rightMode)) {
				if (dropdown.isOpen() && dropdown.click(textRenderer, event.x(), event.y())) {
					return true;
				}
			}
			for (Dropdown<?> dropdown : List.of(leftList, leftMode, rightList, rightMode)) {
				if (dropdown.click(textRenderer, event.x(), event.y())) {
					click();
					return true;
				}
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
	public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
		if (active == Tab.GENERAL && sortOrder.scroll(deltaY)) {
			return true;
		}
		if (active == Tab.NAMETAG) {
			for (Dropdown<?> dropdown : List.of(leftList, leftMode, rightList, rightMode)) {
				if (dropdown.scroll(deltaY)) {
					return true;
				}
			}
		}
		scroll = Math.clamp(scroll - (int) (deltaY * 12), 0, maxScroll);
		return true;
	}

	@Override
	public void close() {
		client.setScreen(parent);
	}

	private record Zone(int left, int top, int right, int bottom, Runnable action) {
		boolean contains(double x, double y) {
			return x >= left && x <= right && y >= top && y <= bottom;
		}
	}
}
