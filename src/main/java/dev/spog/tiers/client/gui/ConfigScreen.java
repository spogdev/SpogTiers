package dev.spog.tiers.client.gui;

import dev.spog.spore.ui.screen.SporeConfigScreen;
import dev.spog.spore.ui.screen.SporeTab;
import dev.spog.spore.ui.theme.SporeTheme;
import dev.spog.spore.ui.widget.SporeDropdown;
import dev.spog.tiers.SpogTiers;
import dev.spog.tiers.SpogTiersClient;
import dev.spog.tiers.compat.NametagTweaks;
import dev.spog.tiers.config.SpogTiersConfig;
import dev.spog.tiers.data.Gamemode;
import dev.spog.tiers.data.Tier;
import dev.spog.tiers.data.TierList;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen settings, built on the shared SporeUI config screen.
 *
 * <p>Four of the five tabs are plain lists of settings, which the library
 * draws: this class only says what rows they hold. The fifth is the nametag
 * editor, which is an editor rather than a list -- it fills the body with
 * panels of its own, scrolls inside one of them, and takes keys and drags --
 * so it is a bare tab and keeps all of its own code.
 */
public class ConfigScreen extends SporeConfigScreen<ConfigScreen.Tab> {
	/** The logo drawn beside a tierlist's name. */
	private static final int LOGO_SIZE = 14;

	public enum Tab implements SporeTab {
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

		@Override
		public String title() {
			return title;
		}
	}

	private SporeDropdown<SpogTiersConfig.SortOrder> sortOrder;

	/** Tiers tab: which palette the HT/MT/LT colours come from. */
	private SporeDropdown<SpogTiersConfig.TierPalette> tierPalette;

	/** Tab tab: how players are ordered, and how ties are broken. */
	private SporeDropdown<SpogTiersConfig.TabSort> tabPrimary;
	private SporeDropdown<SpogTiersConfig.TabSort> tabSecondary;

	/** Tab tab: the player count above which sorting is left alone. */
	private SporeDropdown<Integer> tabLimit;

	/** The nametag tab, which is its own editor rather than a list of rows. */
	private final TagEditor tagEditor = new TagEditor(
			Minecraft.getInstance().font, null);

	{
		tagEditor.onClose(this::onClose);
	}

	public ConfigScreen(Screen parent) {
		super(parent, Component.literal("SpogTiers"), Tab.values(), Tab.GENERAL);
	}

	private SpogTiersConfig config() {
		return SpogTiersClient.config();
	}

	@Override
	protected void init() {
		super.init();

		SpogTiersConfig config = config();

		tabPrimary = new SporeDropdown<>(value -> {
			config.tabPrimarySort = value;
			config.save();
		});
		tabSecondary = new SporeDropdown<>(value -> {
			config.tabSecondarySort = value;
			config.save();
		});
		tabLimit = new SporeDropdown<>(value -> {
			config.tabSortLimit = value;
			config.save();
		});
		sortOrder = new SporeDropdown<>(value -> {
			config.sortOrder = value;
			config.save();
		});
		tierPalette = new SporeDropdown<>(value -> {
			config.tierPalette = value;
			config.save();
		});
	}

	/**
	 * The nametag tab ends a session rather than just closing a screen, so it
	 * supplies its own Save and Close, Cancel and Reset instead of Done.
	 */
	@Override
	protected boolean showsDoneButton(Tab tab) {
		return tab != Tab.NAMETAG;
	}

	/**
	 * The editor fills the body with three panels of its own, so a frame
	 * around them would only draw a line across their tops joining them
	 * together, and the right-hand panel scrolls on its own.
	 */
	@Override
	protected BodyStyle bodyStyle(Tab tab) {
		return tab == Tab.NAMETAG ? BodyStyle.BARE : BodyStyle.FRAMED;
	}

	@Override
	protected void onTabChanged(Tab tab) {
		if (tab == Tab.NAMETAG) {
			// A fresh working copy each time the tab is entered, so Cancel
			// undoes this visit rather than every visit since the screen was
			// opened.
			tagEditor.open();
		}
		tagEditor.closeDropdowns();
	}

	/**
	 * Switches tabs from a client gametest.
	 *
	 * <p>The tab strip is drawn rather than being widgets, so a test has no
	 * button to press: the zones that take the clicks only exist while a frame
	 * is being painted. This is the same switch a click performs.
	 *
	 * @param tab the tab to show
	 */
	public void openTabForTest(Tab tab) {
		setActiveTab(tab);
	}

	@Override
	protected void drawTab(Body body, Tab tab) {
		switch (tab) {
			case GENERAL -> drawGeneral(body);
			case TAB -> drawTabList(body);
			case TIER_LISTS -> drawTierLists(body);
			case INTEGRATIONS -> drawIntegrations(body);
			// Drawn by drawBareTab instead: it is a bare tab.
			case NAMETAG -> {
			}
		}
	}

	@Override
	protected void drawBareTab(GuiGraphicsExtractor graphics, Tab tab, int left, int top,
			int right, int bottom, int mouseX, int mouseY) {
		tagEditor.draw(graphics, left, top, right, bottom, mouseX, mouseY);
		// Lined up with the right panel's edge rather than the screen's
		// margin, so the row of buttons ends where the panels above it do, at
		// the same height Done sits at.
		tagEditor.drawActions(graphics,
				right - DONE_WIDTH,
				height - SporeTheme.MARGIN - DONE_INSET - DONE_HEIGHT,
				DONE_WIDTH, mouseX, mouseY);
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
	private void drawGeneral(Body body) {
		SpogTiersConfig config = config();

		body.section("General");

		sortOrder.setEnumEntries(SpogTiersConfig.SortOrder.values(),
				SpogTiersConfig.SortOrder::title);
		body.dropdown("Sorting", sortOrder, config.sortOrder,
				"How the rows inside each tierlist card are ordered");

		body.section("Toggle Elements");

		body.toggle("Show HQ Placements", config.showPlacements,
				"If placement rounds should be shown in the tier viewer",
				value -> {
					config.showPlacements = value;
					config.save();
				});

		body.toggle("Show Peak Tiers", config.showPeakTiers,
				"Show the peak tier a player has held",
				value -> {
					config.showPeakTiers = value;
					config.save();
				});

		body.toggle("Show Retired", config.showRetired,
				"Show retired ranks as retired, rather than as the plain tier",
				value -> {
					config.showRetired = value;
					config.save();
				});

		body.toggle("Show Discord", config.showDiscord,
				"Show a player's linked Discord account on their profile",
				value -> {
					config.showDiscord = value;
					config.save();
				});

		body.toggle("Show Copy Button", config.showCopyButton,
				"Offer the copy-as-image button on a profile; Close takes its "
						+ "place when it is off",
				value -> {
					config.showCopyButton = value;
					config.save();
				});
	}

	/**
	 * How the tab list is ordered.
	 *
	 * <p>Two sorts rather than one: the first decides a player's place, the
	 * second decides it between players the first leaves level -- a tier sort
	 * puts every HT1 together, and the secondary says in what order.
	 */
	private void drawTabList(Body body) {
		SpogTiersConfig config = config();

		body.section("Tab List Sorting");

		tabPrimary.setEnumEntries(SpogTiersConfig.TabSort.values(),
				SpogTiersConfig.TabSort::title);
		body.dropdown("Primary Sort", tabPrimary, config.tabPrimarySort,
				"What decides a player's place in the tab list");

		tabSecondary.setEnumEntries(SpogTiersConfig.TabSort.values(),
				SpogTiersConfig.TabSort::title);
		body.dropdown("Secondary Sort", tabSecondary, config.tabSecondarySort,
				"The tie-breaker: what orders players the primary sort ranks "
						+ "equally, such as two players on the same tier");

		body.toggle("Sort Spectators", config.tabSortSpectators,
				"Sort spectators with everyone else, rather than leaving them "
						+ "at the end as vanilla does",
				value -> {
					config.tabSortSpectators = value;
					config.save();
				});

		body.toggle("Limit By Players", config.tabSortLimited,
				"Stop sorting once the tab list holds more names than the "
						+ "limit, where the order is mostly noise and the work is not",
				value -> {
					config.tabSortLimited = value;
					config.save();
				});

		// Only offered while the switch is on: a limit with nothing limiting
		// by it reads as a setting that does nothing.
		if (config.tabSortLimited) {
			List<SporeDropdown.Entry<Integer>> limits = new ArrayList<>();
			for (int value : SpogTiersConfig.TAB_SORT_LIMITS) {
				limits.add(new SporeDropdown.Entry<>(value, Integer.toString(value), null));
			}
			tabLimit.setEntries(limits);
			body.dropdown("Player Limit", tabLimit, config.tabSortLimit,
					"Sorting stops above this many names in the tab list, which "
							+ "vanilla caps at 80 however many players are online");
		}
	}

	/**
	 * The Tiers tab: the aura, then which lists are used and where.
	 *
	 * <p>The per-list rows are a grid rather than a list of rows with buttons
	 * on the end: two switches per list, under a heading each, so a column can
	 * be read down to see what every list does in one place.
	 */
	private void drawTierLists(Body body) {
		SpogTiersConfig config = config();

		body.section("Tiers");

		tierPalette.setEnumEntries(SpogTiersConfig.TierPalette.values(),
				SpogTiersConfig.TierPalette::title);
		// The row's y is taken before the dropdown advances it, so the sample
		// lands beside the control rather than under it.
		int paletteY = body.y();
		int sampleX = body.controlX() + SporeTheme.CONTROL_W + 12;
		body.dropdown("Tier Colors", tierPalette, config.tierPalette,
				"Which palette the tier colors are drawn from, everywhere in the mod");
		drawPaletteSample(body, config.tierPalette, sampleX, paletteY);

		body.toggle("Show Particles", config.showParticles,
				"Draw the tier aura around a player, in the world and on their "
						+ "profile",
				value -> {
					config.showParticles = value;
					config.save();
				});

		body.section("Tierlists");

		// A heading over each column. Index 0 is the leftmost, which is the
		// order the two were in before: Tiertagger, then Profile.
		body.columnHeading(Component.literal("Tiertagger"), 0, 2);
		body.columnHeading(Component.literal("Profile"), 1, 2);
		body.endHeadings();

		for (TierList list : TierList.values()) {
			boolean shown = config.isEnabled(list);
			// Whether the list may be quoted on a nametag, and whether it is
			// looked up at all. Hiding a list disables the tag switch with it,
			// since a list nobody queries has nothing to tag.
			boolean tagged = config.isTagged(list);

			int rowY = body.y();
			if (body.rowHovered()) {
				body.highlightRow();
			}
			body.graphics().blit(RenderPipelines.GUI_TEXTURED, logoOf(list),
					body.x(), rowY - 4, 0.0f, 0.0f,
					LOGO_SIZE, LOGO_SIZE, 64, 64, 64, 64);
			body.graphics().text(body.font(), Component.literal(list.displayName()),
					body.x() + LOGO_SIZE + 6, rowY,
					shown ? SporeTheme.TITLE_COLOR : SporeTheme.MUTED_COLOR);

			// Drawn off for a hidden list whatever the stored flag says: the
			// switch is dead while the list is hidden, and showing it on would
			// promise a tag that cannot appear.
			body.columnSwitch(0, 2, tagged && shown, shown, value -> {
				config.setTagged(list, value);
				config.save();
			});
			body.columnSwitch(1, 2, shown, true, value -> {
				config.setEnabled(list, value);
				config.save();
			});

			body.advance(SporeTheme.ROW_HEIGHT);
		}
	}

	/**
	 * Settings that only matter alongside another mod.
	 *
	 * <p>A section per mod, and each says plainly when the mod it is about is
	 * not installed rather than offering a switch that would do nothing.
	 */
	private void drawIntegrations(Body body) {
		SpogTiersConfig config = config();

		body.section("Nametag Tweaks");

		if (!NametagTweaks.present()) {
			body.label("Not installed");
			return;
		}

		body.toggle("Visual Adjustments", config.matchNametagTweaks,
				"If the size, height and color adjustments to the nametag "
						+ "should affect the other lines",
				value -> {
					config.matchNametagTweaks = value;
					config.save();
				});
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
	private void drawPaletteSample(Body body, SpogTiersConfig.TierPalette choice,
			int x, int y) {
		int cursor = x;
		for (int tier = 5; tier >= 1; tier--) {
			String label = "T" + tier;
			int color = Tier.paletteRow(choice, tier)[Tier.Position.MID.ordinal()];
			body.graphics().text(body.font(), Component.literal(label), cursor, y, color);
			cursor += body.font().width(label) + 8;
		}
	}

	// --- The nametag tab's own input and overlays ------------------------
	//
	// The editor is a screen's worth of behaviour inside one tab: it owns
	// dropdowns, text boxes, draggable elements and its own tooltips. The
	// library offers each input to the active tab before taking it, so all of
	// this is a straight forward to the editor, and only while its tab is up.

	@Override
	protected boolean requestTabCursor(GuiGraphicsExtractor graphics, Tab tab,
			int mouseX, int mouseY) {
		if (tab != Tab.NAMETAG) {
			return false;
		}
		// A held element first: while one is on the pointer it does not matter
		// what the pointer happens to be over, the hand stays shut until it is
		// let go.
		if (tagEditor.isHoldingElement()) {
			graphics.requestCursor(hand(HandCursors.closed()));
			return true;
		}
		// A text box next: it sits inside the same panel the rows do, so
		// asking for the hand first would win and the box would never show the
		// caret that says it can be typed in.
		if (tagEditor.isOverText(mouseX, mouseY)) {
			graphics.requestCursor(CursorTypes.IBEAM);
			return true;
		}
		// An element that can be picked up, before the buttons: an element is
		// dragged where a button is pressed, and the cursor is what says so
		// before anyone tries it.
		//
		// The pointing hand rather than an open one. GLFW has no open-hand
		// shape and Windows ships no such cursor to copy -- its only hand is
		// this one -- so a drawn stand-in was never going to match the system
		// it sat beside.
		if (tagEditor.isOverElement(mouseX, mouseY)) {
			graphics.requestCursor(CursorTypes.POINTING_HAND);
			return true;
		}
		for (Dropdown<?> dropdown : tagEditor.dropdowns()) {
			if (dropdown.isHovered(font, mouseX, mouseY)) {
				graphics.requestCursor(CursorTypes.POINTING_HAND);
				return true;
			}
		}
		if (tagEditor.isOverControl(mouseX, mouseY)) {
			graphics.requestCursor(CursorTypes.POINTING_HAND);
			return true;
		}
		return false;
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

	@Override
	protected void drawTabOverlay(GuiGraphicsExtractor graphics, Tab tab,
			int mouseX, int mouseY) {
		if (tab != Tab.NAMETAG) {
			return;
		}
		if (tagEditor.hoverText() != null) {
			drawHoverText(graphics, tagEditor.hoverText(), mouseX, mouseY);
		}
		tagEditor.drawButtonTooltip(graphics, mouseX, mouseY, width, height);
		tagEditor.drawOverlays(graphics, mouseX, mouseY);
		// After the list, since that is what decides which row is under the
		// pointer, and over it so the text is not painted beneath.
		if (tagEditor.optionHoverText() != null) {
			drawHoverText(graphics, tagEditor.optionHoverText(), mouseX, mouseY);
		}
	}

	/**
	 * The editor's tooltip: a bordered card in the same palette as the panels,
	 * kept inside the window.
	 *
	 * <p>Drawn here rather than through the library's own tooltips because the
	 * editor hands back a plain string for a point it has already chosen,
	 * rather than registering a labelled row the way a settings tab does.
	 */
	private void drawHoverText(GuiGraphicsExtractor graphics, String text,
			int mouseX, int mouseY) {
		int boxWidth = font.width(text) + 12;
		int boxHeight = font.lineHeight + 12;
		int boxX = Tooltips.x(mouseX, boxWidth, width);
		int boxY = Math.clamp(mouseY - 8, 4, height - boxHeight - 4);

		graphics.fill(boxX, boxY, boxX + boxWidth, boxY + boxHeight,
				SporeTheme.CARD_BORDER);
		graphics.fill(boxX + 1, boxY + 1, boxX + boxWidth - 1, boxY + boxHeight - 1,
				0xE00E1219);
		graphics.text(font, Component.literal(text), boxX + 6, boxY + 6,
				SporeTheme.LABEL_COLOR);
	}

	@Override
	protected boolean tabMouseClicked(Tab tab, net.minecraft.client.input.MouseButtonEvent event,
			boolean doubled) {
		if (tab != Tab.NAMETAG) {
			return false;
		}
		// Open lists get first refusal, so a click inside one is not taken by
		// whatever it happens to overlap.
		for (Dropdown<?> dropdown : tagEditor.dropdowns()) {
			if (dropdown.isOpen() && dropdown.click(font, event.x(), event.y())) {
				return true;
			}
		}
		for (Dropdown<?> dropdown : tagEditor.dropdowns()) {
			if (dropdown.click(font, event.x(), event.y())) {
				return true;
			}
		}
		return tagEditor.click(event, doubled);
	}

	@Override
	protected boolean tabMouseDragged(Tab tab, net.minecraft.client.input.MouseButtonEvent event,
			double dragX, double dragY) {
		if (tab != Tab.NAMETAG) {
			return false;
		}
		tagEditor.drag(event, dragX, dragY);
		// Not consumed: the editor tracks the drag itself, and the screen has
		// nothing else to do with it either way.
		return false;
	}

	@Override
	protected boolean tabMouseReleased(Tab tab, net.minecraft.client.input.MouseButtonEvent event) {
		return tab == Tab.NAMETAG && tagEditor.release(event.x(), event.y());
	}

	@Override
	protected boolean tabMouseScrolled(Tab tab, double mouseX, double mouseY, double deltaY) {
		if (tab != Tab.NAMETAG) {
			return false;
		}
		for (Dropdown<?> dropdown : tagEditor.dropdowns()) {
			if (dropdown.scroll(deltaY)) {
				return true;
			}
		}
		return tagEditor.scroll(mouseX, mouseY, deltaY);
	}

	@Override
	protected boolean tabKeyPressed(Tab tab, net.minecraft.client.input.KeyEvent event) {
		return tab == Tab.NAMETAG && tagEditor.keyPressed(event);
	}

	@Override
	protected boolean tabCharTyped(Tab tab, net.minecraft.client.input.CharacterEvent event) {
		return tab == Tab.NAMETAG && tagEditor.charTyped(event);
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
}
