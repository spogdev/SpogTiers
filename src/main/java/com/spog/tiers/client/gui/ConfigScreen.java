package com.spog.tiers.client.gui;

import com.spog.tiers.SpogTiers;
import com.spog.tiers.SpogTiersClient;
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

	private Dropdown<TierList> aboveList;
	private Dropdown<Gamemode> aboveMode;
	private Dropdown<TierList> leftList;
	private Dropdown<Gamemode> leftMode;
	private Dropdown<TierList> rightList;
	private Dropdown<Gamemode> rightMode;
	private Dropdown<SpogTiersConfig.SortOrder> sortOrder;
	private Dropdown<SpogTiersConfig.RegionSlot> regionSlot;

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
		aboveList = new Dropdown<>(value -> {
			config.aboveTag.list = value;
			config.aboveTag.gamemode = null;
			config.save();
			click();
		});
		aboveMode = new Dropdown<>(value -> {
			config.aboveTag.gamemode = value;
			config.save();
			click();
		});
		sortOrder = new Dropdown<>(value -> {
			config.sortOrder = value;
			config.save();
			click();
		});
		regionSlot = new Dropdown<>(value -> {
			config.regionSlot = value;
			config.save();
			click();
		});

		addRenderableWidget(new PanelButton(
				width - MARGIN - CARD_PADDING - 90,
				height - MARGIN - CARD_PADDING - 14,
				90, 20,
				Component.literal("Done"),
				button -> onClose()));
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

		// Outside the scissor, so it is never clipped to the body.
		if (hoverLabel != null && hoverLabel.contains(mouseX, mouseY)) {
			drawHoverText(graphics, hoverLabel.text(), mouseX, mouseY);
		}

		super.extractRenderState(graphics, mouseX, mouseY, partialTick);

		// Our controls are drawn rather than being widgets, so vanilla's own
		// hover cursor never applies to them. Requested after the widgets so a
		// control under the pointer wins over the panel behind it.
		requestPointerCursor(graphics, mouseX, mouseY);

		// Open dropdowns paint last so they overlap the rows beneath them.
		if (active == Tab.NAMETAG) {
			SpogTiersConfig config = config();
			aboveList.drawOverlay(graphics, font, config.aboveTag.list, mouseX, mouseY);
			aboveMode.drawOverlay(graphics, font, config.aboveTag.gamemode, mouseX, mouseY);
			leftList.drawOverlay(graphics, font, config.leftTag.list, mouseX, mouseY);
			leftMode.drawOverlay(graphics, font, config.leftTag.gamemode, mouseX, mouseY);
			rightList.drawOverlay(graphics, font, config.rightTag.list, mouseX, mouseY);
			rightMode.drawOverlay(graphics, font, config.rightTag.gamemode, mouseX, mouseY);
			regionSlot.drawOverlay(graphics, font, config.regionSlot, mouseX, mouseY);
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
		boolean over = false;
		for (Dropdown<?> dropdown : dropdowns()) {
			if (dropdown != null && dropdown.isHovered(font, mouseX, mouseY)) {
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
			graphics.requestCursor(CursorTypes.POINTING_HAND);
		}
	}

	/** Every dropdown on the active tab. */
	private List<Dropdown<?>> dropdowns() {
		if (active == Tab.NAMETAG) {
			return List.of(aboveList, aboveMode, leftList, leftMode, rightList, rightMode,
					regionSlot);
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
				click();
			}));
			x += tabWidth + 4;
		}
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
				});

		y = drawSwitch(graphics, "Show Retired", config.showRetired, x, y,
				() -> {
					config.showRetired = !config.showRetired;
					config.save();
				},
				"Show retired ranks as retired, rather than as the plain tier");

		y = drawSwitch(graphics, "Extra Tierlists", config.extraTierlists, x, y,
				() -> {
					config.extraTierlists = !config.extraTierlists;
					config.save();
				});

		y = drawSwitch(graphics, "Particles", config.showParticles, x, y,
				() -> {
					config.showParticles = !config.showParticles;
					config.save();
				},
				"Draw the embers around graded players");

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
	private int drawNametag(GuiGraphicsExtractor graphics, int left, int top, int right,
			int mouseX, int mouseY) {
		SpogTiersConfig config = config();
		int x = left + CARD_PADDING;
		int y = top + CARD_PADDING;

		graphics.text(font, Component.literal("Nametag"), x, y, 0xFFFFFFFF);
		y += font.lineHeight + 10;

		y += drawPreview(graphics, x, y, right) + 8;

		y = drawSlot(graphics, "Above", config.aboveTag, aboveList, aboveMode,
				x, y, mouseX, mouseY);
		y = drawSlot(graphics, "Left", config.leftTag, leftList, leftMode,
				x, y, mouseX, mouseY);
		y = drawSlot(graphics, "Right", config.rightTag, rightList, rightMode,
				x, y, mouseX, mouseY);
		y += 8;

		// A dropdown rather than a switch: the region can sit on either row and
		// either side of it, and Off is one of the five choices.
		graphics.text(font, Component.literal("Region"), x, y + 4, LABEL_COLOR);

		List<Dropdown.Entry<SpogTiersConfig.RegionSlot>> slots = new ArrayList<>();
		for (SpogTiersConfig.RegionSlot value : SpogTiersConfig.RegionSlot.values()) {
			slots.add(new Dropdown.Entry<>(value, value.title(), null));
		}
		regionSlot.setEntries(slots);
		regionSlot.setBounds(x + 118, y, 152);
		regionSlot.draw(graphics, font, config.regionSlot, mouseX, mouseY);
		y += ROW_HEIGHT + 4;
		y = drawSwitch(graphics, "Prevent Duplicates", config.preventDuplicateTiers, x, y,
				() -> {
					config.preventDuplicateTiers = !config.preventDuplicateTiers;
					config.save();
				},
				"Prevent the same tier from being shown multiple times");
		y = drawSwitch(graphics, "Tag Displays", config.tagDisplays, x, y,
				() -> {
					config.tagDisplays = !config.tagDisplays;
					config.save();
				},
				"Tags nametags that a server draws with a text display");
		y = drawSwitch(graphics, "Separators", config.showSeparators, x, y,
				() -> {
					config.showSeparators = !config.showSeparators;
					config.save();
				},
				"Draw a line between each part of the nametag");
		y += font.lineHeight + 10;

		graphics.text(font, Component.literal("Show tiers in"), x, y, 0xFFFFFFFF);
		y += font.lineHeight + 8;

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

		return y + font.lineHeight + CARD_PADDING - top;
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

	private int drawPreview(GuiGraphicsExtractor graphics, int x, int y, int right) {
		SpogTiersConfig config = config();
		// The top row exists if anything is on it. A region placed up there
		// holds the row on its own, exactly as it does in game, so choosing
		// Top Left with the above tag off still previews correctly.
		boolean above = (config.aboveTag != null && config.aboveTag.enabled)
				|| (config.regionSlot.shown() && config.regionSlot.top());

		int boxWidth = Math.min(380, right - CARD_PADDING - x);
		// Room for a second line when the above slot is on, so its tag sits
		// over the name the way it does in the world.
		int boxHeight = above ? 26 + ROW_HEIGHT : 26;
		graphics.fill(x, y, x + boxWidth, y + boxHeight, 0x60101720);
		graphics.fill(x, y, x + boxWidth, y + 1, CARD_BORDER);
		graphics.fill(x, y + boxHeight - 1, x + boxWidth, y + boxHeight, CARD_BORDER);
		graphics.fill(x, y, x + 1, y + boxHeight, CARD_BORDER);
		graphics.fill(x + boxWidth - 1, y, x + boxWidth, y + boxHeight, CARD_BORDER);

		// Whatever is known about him, or a stand-in so the preview still
		// reads before anything has been looked up.
		Tier sample = new Tier(1, Tier.Position.HIGH, false);
		int nameWidth = font.width(PREVIEW_NAME);
		int textY = y + (26 - font.lineHeight) / 2 + (above ? ROW_HEIGHT : 0);

		SpogTiersConfig.RegionSlot slot = config.regionSlot;
		String region = previewRegion();
		int regionSpan = font.width(region) + 5;

		if (above) {
			// Centred over the name, matching how the line is drawn in game,
			// with the region counted in when it shares this row.
			int aboveWidth = previewTagWidth(config.aboveTag, previewTier(config.aboveTag, sample));
			int topSpan = aboveWidth + (slot.shown() && slot.top() ? regionSpan : 0);
			int aboveX = x + 8 + regionWidth(config)
					+ previewTagWidth(config.leftTag, previewTier(config.leftTag, sample))
					+ (nameWidth - topSpan) / 2;
			int topCursor = Math.max(x + 8, aboveX);
			if (slot.shown() && slot.top() && slot.before()) {
				graphics.text(font, Component.literal(region), topCursor,
						textY - ROW_HEIGHT, 0xFF89F19C);
				topCursor += regionSpan;
			}
			topCursor = drawPreviewTag(graphics, config.aboveTag,
					previewTier(config.aboveTag, sample), topCursor, textY - ROW_HEIGHT,
					true, true);
			if (slot.shown() && slot.top() && !slot.before()) {
				graphics.text(font, Component.literal(region), topCursor,
						textY - ROW_HEIGHT, 0xFF89F19C);
			}
		}

		int cursor = x + 8;
		boolean onName = slot.shown() && !slot.top();
		if (onName && slot.before()) {
			graphics.text(font, Component.literal(region), cursor, textY, 0xFF89F19C);
			cursor += regionSpan;
		}
		cursor = drawPreviewTag(graphics, config.leftTag, previewTier(config.leftTag, sample), cursor, textY, true, false);
		graphics.text(font, Component.literal(PREVIEW_NAME), cursor, textY, 0xFFFFFFFF);
		cursor += nameWidth;
		cursor = drawPreviewTag(graphics, config.rightTag, previewTier(config.rightTag, sample), cursor, textY, false, false);
		if (onName && !slot.before()) {
			graphics.text(font, Component.literal(region), cursor, textY, 0xFF89F19C);
		}
		return boxHeight;
	}

	/** His region where it is known, else a stand-in so the row still reads. */
	private String previewRegion() {
		String code = Regions.resolve(SpogTiersClient.cache().allLists(PREVIEW_PLAYER));
		return code.isEmpty() ? "EU" : code;
	}

	/**
	 * The width the region takes before the name, or nothing.
	 *
	 * <p>Only the name row's leading slot counts: it is what pushes everything
	 * after it along. A region on the top row, or after the name, does not
	 * move the tags on this one.
	 */
	private int regionWidth(SpogTiersConfig config) {
		SpogTiersConfig.RegionSlot slot = config.regionSlot;
		return slot.shown() && !slot.top() && slot.before()
				? font.width(previewRegion()) + 5 : 0;
	}

	/**
	 * The tier to show for a slot: his own where it is known, else a stand-in.
	 *
	 * <p>Read straight from the cache and never fetched, since this runs on
	 * the render thread. Before anything has been looked up the preview still
	 * reads correctly, it just shows a sample tier.
	 */
	private Tier previewTier(SpogTiersConfig.TagSlot slot, Tier fallback) {
		if (slot == null) {
			return fallback;
		}
		TierList source = slot.list != null ? slot.list
				: (slot.gamemode != null ? firstListWith(slot.gamemode) : firstEnabledList());
		if (source == null) {
			return fallback;
		}
		var tiers = SpogTiersClient.cache().get(PREVIEW_PLAYER, source);
		if (tiers == null) {
			return fallback;
		}
		Tier tier = slot.gamemode == null ? tiers.best() : tiers.get(slot.gamemode);
		return tier != null && tier.isRanked() ? tier : fallback;
	}

	/** What {@link #drawPreviewTag} will advance by, without drawing it. */
	private int previewTagWidth(SpogTiersConfig.TagSlot slot, Tier sample) {
		if (slot == null || !slot.enabled) {
			return 0;
		}
		int width = font.width(sample.label());
		TierList source = slot.list != null ? slot.list
				: (slot.gamemode != null ? firstListWith(slot.gamemode) : firstEnabledList());
		Gamemode mode = slot.gamemode;
		if (mode == null && source != null) {
			mode = source.gamemodes().stream().findFirst().orElse(null);
		}
		if (source != null && mode != null && source.gamemodes().contains(mode)) {
			width += 13;
		}
		return width;
	}

	private int drawPreviewTag(GuiGraphicsExtractor graphics, SpogTiersConfig.TagSlot slot,
			Tier sample, int cursor, int textY, boolean before, boolean alone) {
		// A null list is the Best option, which still previews.
		if (slot == null || !slot.enabled) {
			return cursor;
		}

		// The above line carries no separator: it is its own label in game,
		// not something sitting beside the name.
		if (!before && !alone) {
			graphics.text(font, Component.literal(" | "), cursor, textY, 0xFF555F6B);
			cursor += font.width(" | ");
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
				graphics.blit(RenderPipelines.GUI_TEXTURED, modeIcon(source, mode),
						cursor, textY - 1, 0.0f, 0.0f, 10, 10, 64, 64, 64, 64);
				cursor += 13;
			}
		}

		String label = sample.label();
		graphics.text(font, Component.literal(label), cursor, textY, sample.color());
		cursor += font.width(label);

		if (before && !alone) {
			graphics.text(font, Component.literal(" | "), cursor, textY, 0xFF555F6B);
			cursor += font.width(" | ");
		}
		return cursor;
	}

	private int drawSlot(GuiGraphicsExtractor graphics, String title, SpogTiersConfig.TagSlot slot,
			Dropdown<TierList> listDropdown, Dropdown<Gamemode> modeDropdown,
			int x, int y, int mouseX, int mouseY) {
		SpogTiersConfig config = config();
		graphics.text(font, Component.literal(title), x, y, LABEL_COLOR);

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
		listDropdown.draw(graphics, font, slot.list, mouseX, mouseY);

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
		modeDropdown.draw(graphics, font, slot.gamemode, mouseX, mouseY);

		return y + ROW_HEIGHT;
	}

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
		int toggleX = x + 118;
		drawToggle(graphics, toggleX, y - 4, 44, on ? "ON" : "OFF", on);
		zones.add(new Zone(toggleX, y - 4, toggleX + 44, y + 12, () -> {
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

	private static Identifier logoOf(TierList list) {
		return Identifier.fromNamespaceAndPath(SpogTiers.MOD_ID, list.logoPath());
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
		if (leftList != null) {
			leftList.close();
			leftMode.close();
			rightList.close();
			rightMode.close();
			sortOrder.close();
			regionSlot.close();
		}
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
			for (Dropdown<?> dropdown : dropdowns()) {
				if (dropdown.scroll(deltaY)) {
					return true;
				}
			}
		}
		scroll = Math.clamp(scroll - (int) (deltaY * 12), 0, maxScroll);
		return true;
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
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
		int boxX = Math.min(mouseX + 12, width - boxWidth - 4);
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
