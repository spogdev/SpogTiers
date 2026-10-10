package dev.spog.tiers.client.gui;

import dev.spog.tiers.SpogTiers;
import dev.spog.tiers.SpogTiersClient;
import dev.spog.tiers.compat.NametagTweaks;
import dev.spog.tiers.config.SpogTiersConfig;
import dev.spog.tiers.data.Gamemode;
import dev.spog.tiers.data.Regions;
import dev.spog.tiers.data.Tier;
import dev.spog.tiers.data.TierList;
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
		// The constants keep their old names: they are referred to all over
		// this class and in the layout code, and renaming them would be a
		// large diff that says nothing. Only the titles are what anyone sees.
		GENERAL("Profiles"),
		TAB("Tab"),
		TIER_LISTS("Tiers"),
		NAMETAG("Tier Tagger"),
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

	/**
	 * The gap between the action buttons and the panel's bottom and right
	 * edges.
	 *
	 * <p>One constant for both, so the button sits the same distance from the
	 * corner either way. The two used to be derived separately -- the bottom
	 * from a hand-written offset and the right from CARD_PADDING -- which left
	 * it six pixels further from the right edge than the bottom and reading as
	 * slightly misplaced.
	 */
	private static final int DONE_INSET = 6;

	/** The action buttons' height, which their placement is measured from. */
	private static final int DONE_HEIGHT = 20;

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

	/** Tiers tab: which palette the HT/MT/LT colours come from. */
	private Dropdown<SpogTiersConfig.TierPalette> tierPalette;

	/** Tab tab: how players are ordered, and how ties are broken. */
	private Dropdown<SpogTiersConfig.TabSort> tabPrimary;
	private Dropdown<SpogTiersConfig.TabSort> tabSecondary;

	/** Tab tab: the player count above which sorting is left alone. */
	private Dropdown<Integer> tabLimit;

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

		tabPrimary = new Dropdown<>(value -> {
			config.tabPrimarySort = value;
			config.save();
		});
		tabSecondary = new Dropdown<>(value -> {
			config.tabSecondarySort = value;
			config.save();
		});
		tabLimit = new Dropdown<>(value -> {
			config.tabSortLimit = value;
			config.save();
		});
		// No click() here: mouseClicked plays one for every consumed dropdown
		// click, so sounding it on pick as well played it twice.
		sortOrder = new Dropdown<>(value -> {
			config.sortOrder = value;
			config.save();
		});
		tierPalette = new Dropdown<>(value -> {
			config.tierPalette = value;
			config.save();
		});

		// The nametag tab ends a session rather than just closing a screen, so
		// it puts its own Save & Close, Cancel and Reset here instead. Done is
		// only added for the tabs that have nothing to commit.
		doneButton = new PanelButton(
				width - MARGIN - DONE_INSET - DONE_WIDTH,
				height - MARGIN - DONE_INSET - DONE_HEIGHT,
				DONE_WIDTH, DONE_HEIGHT,
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
					drawGeneral(graphics, left, originY, right, mouseX, mouseY);
			case TAB -> contentHeight =
					drawTab(graphics, left, originY, mouseX, mouseY);
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
			// The same height off the bottom as Done, which this stands in
			// for: measured from DONE_INSET so the two cannot drift apart.
			tagEditor.drawActions(graphics,
					right - DONE_WIDTH,
					height - MARGIN - DONE_INSET - DONE_HEIGHT,
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
			// After the list, since that is what decides which row is under
			// the pointer, and over it so the text is not painted beneath.
			if (tagEditor.optionHoverText() != null) {
				drawHoverText(graphics, tagEditor.optionHoverText(), mouseX, mouseY);
			}
		} else if (active == Tab.GENERAL) {
			sortOrder.drawOverlay(graphics, font, config().sortOrder, mouseX, mouseY);
		} else if (active == Tab.TIER_LISTS) {
			tierPalette.drawOverlay(graphics, font, config().tierPalette, mouseX, mouseY);
		}
		if (active == Tab.TAB) {
			tabPrimary.drawOverlay(graphics, font, config().tabPrimarySort, mouseX, mouseY);
			tabSecondary.drawOverlay(graphics, font, config().tabSecondarySort,
					mouseX, mouseY);
			if (config().tabSortLimited) {
				tabLimit.drawOverlay(graphics, font, config().tabSortLimit, mouseX, mouseY);
			}
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
		if (active == Tab.TAB) {
			// The limit is only listed while it is in use, so a click where it
			// would be does not land on a control that is not drawn.
			return config().tabSortLimited
					? List.of(tabPrimary, tabSecondary, tabLimit)
					: List.of(tabPrimary, tabSecondary);
		}
		if (active == Tab.TIER_LISTS) {
			return List.of(tierPalette);
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
	 * How the tab list is ordered.
	 *
	 * <p>Two sorts rather than one: the first decides a player's place, the
	 * second decides it between players the first leaves level -- a tier sort
	 * puts every HT1 together, and the secondary says in what order.
	 */
	private int drawTab(GuiGraphicsExtractor graphics, int left, int top,
			int mouseX, int mouseY) {
		SpogTiersConfig config = config();
		int x = left + CARD_PADDING;
		int y = top + CARD_PADDING;

		graphics.text(font, Component.literal("Tab List Sorting"), x, y, 0xFFFFFFFF);
		y += font.lineHeight + 10;

		List<Dropdown.Entry<SpogTiersConfig.TabSort>> sorts = new ArrayList<>();
		for (SpogTiersConfig.TabSort value : SpogTiersConfig.TabSort.values()) {
			sorts.add(new Dropdown.Entry<>(value, value.title(), null));
		}

		graphics.text(font, Component.literal("Primary Sort"), x, y, LABEL_COLOR);
		tabPrimary.setEntries(sorts);
		tabPrimary.setBounds(x + 118, y - 4, 152);
		tabPrimary.draw(graphics, font, config.tabPrimarySort, mouseX, mouseY);
		hoverFor(x, y, "Primary Sort", "What decides a player's place in the tab list");
		// No extra gap before the secondary: the two are one setting in two
		// parts, and spacing them apart read as two unrelated rows.
		y += ROW_HEIGHT;

		graphics.text(font, Component.literal("Secondary Sort"), x, y, LABEL_COLOR);
		tabSecondary.setEntries(sorts);
		tabSecondary.setBounds(x + 118, y - 4, 152);
		tabSecondary.draw(graphics, font, config.tabSecondarySort, mouseX, mouseY);
		hoverFor(x, y, "Secondary Sort",
				"The tie-breaker: what orders players the primary sort ranks "
						+ "equally, such as two players on the same tier");
		y += ROW_HEIGHT + 8;

		y = drawSwitch(graphics, "Sort Spectators", config.tabSortSpectators, x, y,
				() -> {
					config.tabSortSpectators = !config.tabSortSpectators;
					config.save();
				},
				"Sort spectators with everyone else, rather than leaving them "
						+ "at the end as vanilla does");

		y = drawSwitch(graphics, "Limit By Players", config.tabSortLimited, x, y,
				() -> {
					config.tabSortLimited = !config.tabSortLimited;
					config.save();
				},
				"Stop sorting once the tab list holds more names than the "
						+ "limit, where the order is mostly noise and the work is not");

		// Only offered while the switch is on: a limit with nothing limiting
		// by it reads as a setting that does nothing.
		if (config.tabSortLimited) {
			graphics.text(font, Component.literal("Player Limit"), x, y, LABEL_COLOR);
			List<Dropdown.Entry<Integer>> limits = new ArrayList<>();
			for (int value : SpogTiersConfig.TAB_SORT_LIMITS) {
				limits.add(new Dropdown.Entry<>(value, Integer.toString(value), null));
			}
			tabLimit.setEntries(limits);
			tabLimit.setBounds(x + 118, y - 4, 152);
			tabLimit.draw(graphics, font, config.tabSortLimit, mouseX, mouseY);
			hoverFor(x, y, "Player Limit",
					"Sorting stops above this many names in the tab list, which "
							+ "vanilla caps at 80 however many players are online");
			y += ROW_HEIGHT + 8;
		}

		return y + CARD_PADDING - top;
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

	/**
	 * The Profiles tab: how a profile is ordered, then what it shows.
	 *
	 * <p>Two sections. General holds the one setting that is about ordering
	 * rather than content; everything that turns a part of the profile on or
	 * off is a Toggle Elements row, which is a long list and reads better
	 * under its own heading than appended to a section about sorting.
	 *
	 * <p>Show Particles is not here: it is about the tier aura, so it lives
	 * with the tier settings on the Tiers tab.
	 */
	private int drawGeneral(GuiGraphicsExtractor graphics, int left, int top,
			int right, int mouseX, int mouseY) {
		SpogTiersConfig config = config();
		int x = left + CARD_PADDING;
		int y = top + CARD_PADDING;

		y = drawSection(graphics, "General", x, y, right, true);

		graphics.text(font, Component.literal("Sorting"), x, y, LABEL_COLOR);

		List<Dropdown.Entry<SpogTiersConfig.SortOrder>> orders = new ArrayList<>();
		for (SpogTiersConfig.SortOrder value : SpogTiersConfig.SortOrder.values()) {
			orders.add(new Dropdown.Entry<>(value, value.title(), null));
		}
		sortOrder.setEntries(orders);
		sortOrder.setBounds(x + 118, y - 4, 152);
		sortOrder.draw(graphics, font, config.sortOrder, mouseX, mouseY);
		hoverFor(x, y, "Sorting", "How the rows inside each tierlist card are ordered");

		// Plain ROW_HEIGHT, not the usual ROW_HEIGHT + 8: the extra eight
		// holds a dropdown apart from the row under it, and what follows here
		// is a divider that brings its own space.
		y += ROW_HEIGHT;

		y = drawSection(graphics, "Toggle Elements", x, y, right, false);

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

		y = drawSwitch(graphics, "Show Copy Button", config.showCopyButton, x, y,
				() -> {
					config.showCopyButton = !config.showCopyButton;
					config.save();
				},
				"Offer the copy-as-image button on a profile; Close takes its "
						+ "place when it is off");

		return y + CARD_PADDING - top;
	}

	/** How far apart the Tiertagger and Profile columns of switches sit. */
	private static final int TIER_COLUMN_GAP = 86;

	/**
	 * How far in from the panel's right edge the Profile column sits.
	 *
	 * <p>The pair used to end at CARD_PADDING, flush with the rule above them,
	 * which pushed both columns hard against the edge. Pulling them in leaves
	 * the switches clear of it without changing the gap between the two.
	 */
	private static final int TIER_COLUMN_INSET = 24;

	/**
	 * The Tiers tab: the aura, then which lists are used and where.
	 *
	 * <p>The per-list rows are a grid rather than a list of rows with buttons
	 * on the end: two switches per list, under a heading each, so a column can
	 * be read down to see what every list does in one place. The lettered
	 * boxes they replace said SHOWN/HIDDEN on every row, which is the same
	 * word twelve times over where a switch's position says it already.
	 */
	private int drawTierLists(GuiGraphicsExtractor graphics, int left, int top, int right,
			int mouseX, int mouseY) {
		SpogTiersConfig config = config();
		int x = left + CARD_PADDING;
		int y = top + CARD_PADDING;

		y = drawSection(graphics, "Tiers", x, y, right, true);

		graphics.text(font, Component.literal("Tier Colors"), x, y, LABEL_COLOR);

		List<Dropdown.Entry<SpogTiersConfig.TierPalette>> palettes = new ArrayList<>();
		for (SpogTiersConfig.TierPalette value : SpogTiersConfig.TierPalette.values()) {
			palettes.add(new Dropdown.Entry<>(value, value.title(), null));
		}
		tierPalette.setEntries(palettes);
		tierPalette.setBounds(x + 118, y - 4, 152);
		tierPalette.draw(graphics, font, config.tierPalette, mouseX, mouseY);
		drawPaletteSample(graphics, config.tierPalette, x + 118 + 152 + 12, y);
		hoverFor(x, y, "Tier Colors",
				"Which palette the tier colors are drawn from, everywhere in the mod");

		y += ROW_HEIGHT + 8;

		y = drawSwitch(graphics, "Show Particles", config.showParticles, x, y,
				() -> {
					config.showParticles = !config.showParticles;
					config.save();
				},
				"Draw the tier aura around a player, in the world and on their "
						+ "profile");

		y = drawSection(graphics, "Tierlists", x, y, right, false);

		// The two columns, placed from the right edge so they stay put as the
		// window changes width. Measured once here rather than per row, so the
		// headings and every switch below them cannot drift apart.
		int profileX = right - TIER_COLUMN_INSET - SLIDER_WIDTH;
		int taggerX = profileX - TIER_COLUMN_GAP;

		// A heading over each column. Centred on its switches, because a label
		// left-aligned to a 22-pixel control reads as belonging to whatever is
		// to its left instead.
		drawColumnLabel(graphics, "Tiertagger", taggerX, y);
		drawColumnLabel(graphics, "Profile", profileX, y);
		y += font.lineHeight + 6;

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

			// Whether the list may be quoted on a nametag, and whether it is
			// looked up at all. Hiding a list disables the tag switch with it,
			// since a list nobody queries has nothing to tag.
			boolean tagged = config.isTagged(list);

			// Drawn off for a hidden list whatever the stored flag says: the
			// switch is dead while the list is hidden, and showing it on would
			// promise a tag that cannot appear.
			drawSlider(graphics, taggerX, y - 2, tagged && shown, shown);
			if (shown) {
				zones.add(new Zone(taggerX, y - 4, taggerX + SLIDER_WIDTH, y + 12, () -> {
					config.setTagged(list, !tagged);
					config.save();
					click();
				}));
			}

			drawSlider(graphics, profileX, y - 2, shown);
			zones.add(new Zone(profileX, y - 4, profileX + SLIDER_WIDTH, y + 12, () -> {
				config.setEnabled(list, !shown);
				config.save();
				click();
			}));

			y += ROW_HEIGHT;
		}
		return y + CARD_PADDING - top;
	}

	/**
	 * The palette's tiers spelled out beside the dropdown, best last.
	 *
	 * <p>Drawn as the labels themselves in their own colours rather than as
	 * plain swatches: the point of the setting is how a tier code will look
	 * where it is read, and a row of squares does not show that. T1 sits at the
	 * right-hand end so the row runs the way the lists print a ladder, worst
	 * first.
	 *
	 * <p>MT shades are used, which are the midpoints of each tier's HT and LT,
	 * so one example stands for the three colours that tier can appear in.
	 */
	private void drawPaletteSample(GuiGraphicsExtractor graphics,
			SpogTiersConfig.TierPalette choice, int x, int y) {
		int cursor = x;
		for (int tier = 5; tier >= 1; tier--) {
			String label = "T" + tier;
			int color = Tier.paletteRow(choice, tier)[Tier.Position.MID.ordinal()];
			graphics.text(font, Component.literal(label), cursor, y, color);
			cursor += font.width(label) + 8;
		}
	}

	/** A column heading, centred over a switch at {@code x}. */
	private void drawColumnLabel(GuiGraphicsExtractor graphics, String title,
			int x, int y) {
		graphics.text(font, Component.literal(title),
				x + SLIDER_WIDTH / 2 - font.width(title) / 2, y, LABEL_COLOR);
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





	/**
	 * A section heading, and the y to carry on drawing at.
	 *
	 * <p>The first one in a tab is its title; the ones after it divide the
	 * body up. Drawn the same either way, with a rule above every heading but
	 * the first so the divisions read as sections rather than as stray bold
	 * text -- a tab with one section looks exactly as it did before.
	 */
	private int drawSection(GuiGraphicsExtractor graphics, String title,
			int x, int y, int right, boolean first) {
		if (!first) {
			// No gap added above the rule. The row before it already ends with
			// one: a switch row advances by ROW_HEIGHT but its text is only
			// lineHeight tall, so eleven pixels of blank are there already,
			// against the nine below the rule. Adding more on top put the
			// divider visibly nearer the section it introduces than the one
			// it closes.
			graphics.fill(x, y, right - CARD_PADDING, y + 1, 0xFF2A3341);
			y += 9;
		}
		graphics.text(font, Component.literal(title), x, y, 0xFFFFFFFF);
		return y + font.lineHeight + 10;
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
		// The nametag editor's sliding switch rather than a lettered box, so
		// the two tabs read the same way.
		int toggleX = x + 118;
		drawSlider(graphics, toggleX, y - 2, on);
		zones.add(new Zone(toggleX, y - 4, toggleX + SLIDER_WIDTH, y + 12, () -> {
			onClick.run();
			click();
		}));
		hoverFor(x, y, title, description);
		return y + ROW_HEIGHT;
	}

	/**
	 * Records the tooltip for a labelled row, to be drawn after the panel.
	 *
	 * <p>Recorded rather than drawn here: the body is scissored, so a tooltip
	 * drawn now would be clipped to the panel.
	 *
	 * <p>Kept only when nothing else has already claimed the cursor, so that
	 * two explained rows next to each other each show their own line rather
	 * than the later one overwriting the earlier.
	 */
	private void hoverFor(int x, int y, String title, String description) {
		if (description == null) {
			return;
		}
		HoverLabel candidate =
				new HoverLabel(x, y - 2, x + font.width(title), y + 10, description);
		if (hoverLabel == null || candidate.contains(hoverMouseX, hoverMouseY)) {
			hoverLabel = candidate;
		}
	}

	/**
	 * A sliding switch: green when on, red when off, with the knob at the
	 * end it is set to. The same control the nametag editor draws.
	 *
	 * <p>Ringed in white while the pointer is on it, so a switch says it can
	 * be clicked the way the tabs and buttons already do.
	 */
	private void drawSlider(GuiGraphicsExtractor graphics, int x, int y, boolean on) {
		drawSlider(graphics, x, y, on, true);
	}

	/**
	 * As above, but greyed and inert when {@code enabled} is false.
	 *
	 * <p>For a switch whose setting cannot apply yet -- a list's nametag
	 * switch while the list itself is hidden. Drawn rather than hidden, so the
	 * grid keeps its shape and the row does not appear to be missing a
	 * control; it just plainly cannot be used.
	 */
	private void drawSlider(GuiGraphicsExtractor graphics, int x, int y, boolean on,
			boolean enabled) {
		int height = 12;
		int fill = !enabled ? 0xFF2A3341 : on ? SLIDER_ON : SLIDER_OFF;
		graphics.fill(x, y, x + SLIDER_WIDTH, y + height, fill);
		int knob = on ? x + SLIDER_WIDTH - 10 : x + 2;
		graphics.fill(knob, y + 2, knob + 8, y + height - 2,
				enabled ? 0xFFFFFFFF : 0xFF59636F);
		if (!enabled) {
			return;
		}
		// Measured against the zone that takes the click, not the painted
		// switch: the zone is the taller of the two, so ringing the fill would
		// light up only after the pointer was already well inside it.
		if (hoverMouseX >= x && hoverMouseX < x + SLIDER_WIDTH
				&& hoverMouseY >= y - 2 && hoverMouseY < y + height + 2) {
			ring(graphics, x - 1, y - 1, x + SLIDER_WIDTH + 1, y + height + 1);
		}
	}

	/**
	 * A one-pixel outline, drawn just outside what it marks.
	 *
	 * <p>Four edges rather than {@link #drawFrame}, which fills its middle: a
	 * ring has to leave the switch underneath it visible.
	 */
	private void ring(GuiGraphicsExtractor graphics, int left, int top, int right,
			int bottom) {
		graphics.fill(left, top, right, top + 1, 0xFFFFFFFF);
		graphics.fill(left, bottom - 1, right, bottom, 0xFFFFFFFF);
		graphics.fill(left, top, left + 1, bottom, 0xFFFFFFFF);
		graphics.fill(right - 1, top, right, bottom, 0xFFFFFFFF);
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
		tierPalette.close();
		tabPrimary.close();
		tabSecondary.close();
		tabLimit.close();
		tagEditor.closeDropdowns();
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubled) {
		// Dropdowns first: an open list sits above everything else.
		if (active == Tab.TAB && (tabPrimary.click(font, event.x(), event.y())
				|| tabSecondary.click(font, event.x(), event.y())
				|| (config().tabSortLimited
						&& tabLimit.click(font, event.x(), event.y())))) {
			click();
			return true;
		}
		if (active == Tab.GENERAL && sortOrder.click(font, event.x(), event.y())) {
			click();
			return true;
		}
		if (active == Tab.TIER_LISTS && tierPalette.click(font, event.x(), event.y())) {
			click();
			return true;
		}
		if (active == Tab.NAMETAG) {
			// Open lists get first refusal, so a click inside one is not taken
			// by whatever it happens to overlap. This is the branch a pick
			// goes through, so it sounds like the rest of them.
			for (Dropdown<?> dropdown : dropdowns()) {
				if (dropdown.isOpen() && dropdown.click(font, event.x(), event.y())) {
					click();
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
		if (active == Tab.TAB && (tabPrimary.scroll(deltaY) || tabSecondary.scroll(deltaY)
				|| (config().tabSortLimited && tabLimit.scroll(deltaY)))) {
			return true;
		}
		if (active == Tab.GENERAL && sortOrder.scroll(deltaY)) {
			return true;
		}
		if (active == Tab.TIER_LISTS && tierPalette.scroll(deltaY)) {
			return true;
		}
		if (active == Tab.NAMETAG) {
			for (Dropdown<?> dropdown : dropdowns()) {
				if (dropdown.scroll(deltaY)) {
					return true;
				}
			}
			if (tagEditor.scroll(mouseX, mouseY, deltaY)) {
				return true;
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
