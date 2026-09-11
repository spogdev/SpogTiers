package com.spog.tiers.client.gui;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import com.spog.tiers.SpogTiers;
import com.spog.tiers.SpogTiersClient;
import com.spog.tiers.client.ModeIcons;
import com.spog.tiers.client.QuickTiers;
import com.spog.tiers.config.SpogTiersConfig;
import com.spog.tiers.data.DiscordAccount;
import com.spog.tiers.data.Gamemode;
import com.spog.tiers.data.NameHistory;
import com.spog.tiers.data.PlayerGrade;
import com.spog.tiers.data.PlayerTiers;
import com.spog.tiers.data.Regions;
import com.spog.tiers.data.Tier;
import com.spog.tiers.data.TierDetail;
import com.spog.tiers.data.TierList;
import com.spog.tiers.data.TierService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.PlayerSkin;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Full-screen profile view: a profile card down the left with the player skin,
 * and one fixed-size card per tier list they are ranked in.
 *
 * <p>The skin is rendered from the player GameProfile rather than from an
 * entity, so it works for anyone -- including players not on the current
 * server, or when not connected to one at all. The profile must carry its
 * {@code textures} property or the model falls back to the default skin.
 */
public class ProfileScreen extends Screen {
	private static final int MARGIN = 10;
	private static final int ROW_HEIGHT = 16;
	private static final int FACE_SIZE = 20;

	/** The gap under the name row, before the model starts. */
	private static final int HEADER_GAP = 12;

	/**
	 * How far below the face row the Discord line sits.
	 *
	 * <p>Clear of the face rather than tucked against it: the name row is
	 * centred on a 20-pixel face, so a line placed one line-height under the
	 * name still overlapped the bottom of it.
	 */
	private static final int DISCORD_DROP = 6;

	/**
	 * How much taller the header is when a Discord account is shown.
	 *
	 * <p>Reserved from the moment there is a name to draw, and given back when
	 * there is not: the answer arrives a frame or two after the screen opens,
	 * and a header that grew on arrival would shove the model down as you
	 * watched.
	 */
	private static final int DISCORD_ROW = DISCORD_DROP + 9 + 2;
	/**
	 * The profile panel's width in GUI space, and the share of the window it
	 * is allowed to grow to.
	 *
	 * <p>The constant is a GUI-space figure, so the panel takes a larger share
	 * of the screen the higher the GUI scale runs: about a quarter at scale 4,
	 * but two fifths at scale 6, which leaves the cards beside it tiny. Scale 4
	 * is the proportion worth keeping, so past it the panel narrows instead.
	 */
	private static final int PROFILE_WIDTH = 172;
	private static final float PROFILE_WIDTH_SHARE = 0.27f;
	private static final int SKIN_WIDTH = 110;
	private static final int SKIN_HEIGHT = 170;
	/** Rows of past names shown under the model before scrolling is needed. */
	private static final int HISTORY_ROWS = 5;
	private static final int HISTORY_ROW_HEIGHT = 11;
	/** Room kept to the right of the history rows for its scrollbar. */
	private static final int SCROLLBAR_GUTTER = 6;
	/**
	 * Gap between the last history row and the button row.
	 *
	 * <p>The band hangs from this edge, so both the filled and the empty state
	 * finish the same distance above the buttons.
	 */
	private static final int HISTORY_BUTTON_GAP = 4;
	private static final int CARD_PADDING = 10;
	private static final int CARD_GAP = 10;
	/** Cards are a fixed size so two lists look the same as four. */
	private static final int CARD_WIDTH = 162;
	/**
	 * Rows a card is sized for on screen, whatever it actually holds, so the
	 * grid does not resize as lists arrive.
	 */
	private static final int CARD_ROWS = 11;
	/** Narrowest a card is allowed to get, so the header still reads. */
	private static final int CARD_WIDTH_MIN = 108;
	/** Cards deeper than this keep the full width. */
	private static final int NARROW_ROW_LIMIT = 3;
	private static final int LOGO_SIZE = 14;
	private static final int MODE_ICON = 12;
	private static final int TOOLTIP_PADDING = 6;
	/** Breathing room around the exported picture. */
	private static final int EXPORT_PADDING = 6;
	/** Smallest the model is drawn at in a picture. */
	private static final int EXPORT_SKIN_MIN = 90;
	private static final int LABEL_COLOR = 0xFFB9C4D0;
	private static final int MUTED_COLOR = 0xFF6C7683;
	/** Placement runs read as pending rather than as a rank. */
	private static final int PLACEMENT_COLOR = 0xFF7FA8C9;
	private static final int CARD_FILL = 0x50161B22;
	private static final int CARD_BORDER = 0x70323B47;

	private static final DateTimeFormatter DATE_FORMAT =
			DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.systemDefault());

	private final UUID target;
	private final String playerName;
	private final GameProfile profile;

	private Supplier<PlayerSkin> skin;
	/** Row under the cursor this frame, resolved during card layout. */
	private Hover hover;
	/** List whose header is under the cursor, for the response-time tooltip. */
	private TierList headerHover;
	/** Region tag bounds, so hovering it can name the region in full. */
	private int tagLeft;
	private int tagTop;
	private int tagRight;
	private int tagBottom;

	/**
	 * Where the "n other accounts" marker was drawn, so it can be hovered.
	 *
	 * <p>Recorded during drawing rather than computed again: the header is
	 * laid out at full width and drawn scaled, so anything measuring it a
	 * second time has to repeat that transform and would drift from it.
	 */
	private int discordMoreLeft;
	private int discordMoreTop;
	private int discordMoreRight;
	private int discordMoreBottom;
	/** The names behind that marker. */
	private List<String> discordMore = List.of();

	/**
	 * Where the name and the Discord handle were drawn, so a click can copy
	 * them.
	 *
	 * <p>Recorded while drawing rather than measured again: the header is laid
	 * out at full width and drawn under a scale, so anything measuring it a
	 * second time has to repeat that transform and would drift from it.
	 */
	private int nameCopyLeft;
	private int nameCopyTop;
	private int nameCopyRight;
	private int nameCopyBottom;
	private int discordCopyLeft;
	private int discordCopyTop;
	private int discordCopyRight;
	private int discordCopyBottom;

	private String tagRegion = "";

	/** Door SMP grade tag bounds, for the same reason. */
	private int gradeLeft;
	private int gradeTop;
	private int gradeRight;
	private int gradeBottom;
	private boolean gradeShown;
	private String gradeLabel = "";
	private int gradeColor;
	private PanelButton closeButton;
	private IconButton refreshButton;
	private IconButton copyButton;
	/**
	 * Set for the single frame being captured. While it is set the screen
	 * draws only the profile itself: no dimmed backdrop, no buttons, no
	 * tooltips, so none of it lands in the picture.
	 */
	private boolean exporting;
	/** Where the exported picture starts and how far it runs, in GUI space. */
	private int exportLeft;
	private int exportTop;
	private int exportRight;
	private int exportBottom;
	/** Where the card grid ends, measured as it is laid out. */
	private int cardsRight;
	private int cardsBottom;
	/** Set between arming an export and the frame that gets captured. */
	private boolean captureQueued;
	/** True once the stripped-down frame has been drawn and submitted. */
	private boolean cleanFrameDrawn;
	/** Vertical band the name history occupies, set during layout. */
	private int historyTop;
	private int historyBottom;
	private AnimatedSkinWidget skinWidget;
	/** Embers around the model, in the player's Door SMP tier colour. */
	private final TierAura aura = new TierAura();
	/** The model's on-screen y, restored after a capture. */
	private int skinScreenY;
	/** Highest the model may sit: just below the name row. */
	private int skinTopLimit;
	private int skinModelHeight;
	/** Where the panel frame was last drawn to. */
	private int panelBottom;
	/** Height of the card grid, which the panel matches. */
	private int cardsHeight;
	/** The scale the cards were last drawn at; tooltips follow it. */
	private float cardScale = 1.0f;
	private int historyScroll;
	/** Rows that did not fit, so the wheel knows how far it may travel. */
	private int historyMaxScroll;

	public ProfileScreen(GameProfile profile) {
		super(Component.literal(profile.name()));
		this.profile = profile;
		this.target = profile.id();
		this.playerName = profile.name();
	}

	@Override
	protected void init() {
		Minecraft client = Minecraft.getInstance();

		// Queued here rather than before the screen opens: the screen shows a
		// loading state and fills in when the data lands, so it appears at once.
		SpogTiersClient.service().requestNow(target);
		SpogTiersClient.service().requestNameHistory(target);

		// createLookup fetches from Mojang in the background and serves a default
		// skin until it arrives, so this is safe for arbitrary profiles.
		//
		// The flag is "secure only": with true, skins whose textures property is
		// unsigned are filtered out and replaced by the default skin. The
		// session server hands us unsigned properties, so this must be false or
		// every looked-up player renders as Steve/Alex.
		skin = client.getSkinManager().createLookup(profile, false);

		int cardLeft = MARGIN;
		int cardTop = MARGIN;
		int cardBottom = height - MARGIN;

		// The name history sits between the model and the close button, so it is
		// carved out first and the model gets whatever remains. On a very short
		// window the model has a minimum height and would grow back into this
		// band, so the history yields rather than being drawn over.
		historyBottom = cardBottom - CARD_PADDING - 20 - HISTORY_BUTTON_GAP;
		historyTop = Math.max(
				cardTop + CARD_PADDING + FACE_SIZE + HEADER_GAP + discordRoom() + 80 + 8,
				historyBottom - historyBandHeight());

		// Fit the model to whatever is left between the header and the history,
		// so it never spills out of the profile card.
		int skinTop = cardTop + CARD_PADDING + FACE_SIZE + HEADER_GAP + discordRoom();
		int skinBottom = historyTop - 8;
		int skinHeight = Math.clamp(skinBottom - skinTop, 80, SKIN_HEIGHT);

		AnimatedSkinWidget skinWidget = new AnimatedSkinWidget(
				skinWidgetWidth(), skinHeight, client.getEntityModels(), skin);
		int skinY = skinTop + Math.max(0, (skinBottom - skinTop - skinHeight) / 2);
		skinWidget.setPosition(cardLeft + (profileWidth() - skinWidgetWidth()) / 2, skinY);
		addRenderableWidget(skinWidget);

		// Kept so the export can re-centre the model in the shorter panel and
		// put it straight back afterwards.
		this.skinWidget = skinWidget;
		this.skinScreenY = skinY;
		this.skinTopLimit = skinTop;
		this.skinModelHeight = skinHeight;

		// Close takes the row, less two squares on the right: copy, then
		// refresh.
		int buttonTop = cardBottom - CARD_PADDING - 20;
		int inset = Math.round(CARD_PADDING * panelScale());
		int rowWidth = profileWidth() - inset * 2;
		// The icons stay square and legible rather than shrinking with the
		// panel; only the row they sit in narrows.
		int iconSize = 20;

		closeButton = new PanelButton(
				cardLeft + inset,
				buttonTop,
				rowWidth - iconSize * 2 - 8,
				20,
				Component.literal("Close"),
				button -> onClose());
		addRenderableWidget(closeButton);

		copyButton = new IconButton(
				cardLeft + inset + rowWidth - iconSize * 2 - 4,
				buttonTop,
				iconSize,
				20,
				Component.literal("Copy as image"),
				IconButton.Glyph.COPY,
				button -> beginExport());
		addRenderableWidget(copyButton);

		refreshButton = new IconButton(
				cardLeft + inset + rowWidth - iconSize,
				buttonTop,
				iconSize,
				20,
				Component.literal("Refresh"),
				button -> refresh());
		addRenderableWidget(refreshButton);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		// NB: the blurred background is drawn for us by the framework, which
		// calls extractBackground immediately before this method. Blurring again
		// here throws "Can only blur once per frame".
		// The clean frame has been submitted by the time the next one starts,
		// so the readback is requested here rather than mid-extract.
		if (exporting && cleanFrameDrawn && captureQueued) {
			finishExport();
		}

		// A solid fill while exporting: the usual translucent wash would let
		// the world show through into the picture.
		graphics.fill(0, 0, width, height, exporting ? 0xFF0B0E13 : 0xC00B0E13);

		hover = null;
		headerHover = null;
		aura.tick(partialTick);
		refitSkin();
		layoutButtons();
		// Cards first: the panel sizes itself against them when exporting, and
		// drawing them second would leave it a frame behind. Both are plain
		// fills, so the order does not change what the frame looks like.
		// Hovers are suppressed during a capture so no row highlights itself
		// in the picture.
		drawCards(graphics, exporting ? -1 : mouseX, exporting ? -1 : mouseY);
		drawHeader(graphics);
		// Name history is a browsing aid, not part of the ranking picture.
		if (!exporting) {
			drawNameHistory(graphics);
		}

		if (exporting) {
			// The skin model is a widget, so it has to render for the picture
			// to contain it -- but the buttons must not.
			closeButton.visible = false;
			copyButton.visible = false;
			refreshButton.visible = false;
			// Centre the model in the shortened panel; without this it stays
			// where the taller on-screen layout put it and leaves a gap
			// underneath.
			if (skinWidget != null) {
				// Fit the model to the panel rather than the window, then
				// centre it in what is left.
				int band = exportPanelBottom() - CARD_PADDING - skinTopLimit;
				int fitted = Math.clamp(band, EXPORT_SKIN_MIN, SKIN_HEIGHT);
				skinWidget.setHeight(fitted);
				skinWidget.setY(skinTopLimit + Math.max(0, (band - fitted) / 2));
			}
			super.extractRenderState(graphics, -1, -1, partialTick);
			if (skinWidget != null) {
				skinWidget.setHeight(skinModelHeight);
				skinWidget.setY(skinScreenY);
			}
			closeButton.visible = true;
			copyButton.visible = true;
			refreshButton.visible = true;
			// Now that the stripped-down frame has been laid out, the panel
			// and the cards are at their exported sizes and can be measured.
			int right = Math.max(MARGIN + profileWidth(), cardsRight);
			int bottom = Math.max(panelBottom, cardsBottom);
			exportLeft = Math.max(0, MARGIN - EXPORT_PADDING);
			exportTop = Math.max(0, MARGIN - EXPORT_PADDING);
			exportRight = Math.min(width, right + EXPORT_PADDING);
			exportBottom = Math.min(height, bottom + EXPORT_PADDING);

			// Mark it drawn; the readback happens as the next frame begins.
			cleanFrameDrawn = true;
			return;
		}

		// Widgets (the skin model included) render after our fills, so they are
		// not painted over.
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);

		// The rest of the embers, over the model now that it has drawn, so the
		// effect wraps the player rather than sitting flat behind them.
		// Not gated on the Particles setting: that hides the aura in the world,
		// where it sits on other players and is a matter of taste. Here it is
		// part of how a graded profile looks, and turning it off left the card
		// looking like the lookup had failed.
		if (skinWidget != null && !exporting && SpogTiersClient.config().extraTierlists) {
			aura.draw(graphics, SpogTiersClient.service().grade(target),
					skinWidget.getX(), skinWidget.getY(),
					skinWidget.getWidth(), skinWidget.getHeight(), true);
		}

		// Tooltip last and outside the card transform, so it is never clipped
		// or scaled with the grid.
		// Tested against the pointer rather than the widget's own flag: the
		// flag is only set while the screen is handling input, and the export
		// frame renders with none.
		if (copyButton != null && copyButton.isMouseOver(mouseX, mouseY)) {
			drawLabelTooltip(graphics, "Copy", mouseX, mouseY);
		} else if (refreshButton != null && refreshButton.isMouseOver(mouseX, mouseY)) {
			drawLabelTooltip(graphics, "Refresh", mouseX, mouseY);
		} else if (hover != null) {
			drawTierTooltip(graphics, hover, mouseX, mouseY);
		} else if (headerHover != null) {
			drawResponseTooltip(graphics, headerHover, mouseX, mouseY);
		} else if (gradeShown
				&& mouseX >= gradeLeft && mouseX <= gradeRight
				&& mouseY >= gradeTop && mouseY <= gradeBottom) {
			drawGradeTooltip(graphics, mouseX, mouseY);
		} else if (!tagRegion.isEmpty()
				&& mouseX >= tagLeft && mouseX <= tagRight
				&& mouseY >= tagTop && mouseY <= tagBottom) {
			drawRegionTooltip(graphics, mouseX, mouseY);
		}

		// The marker says how many other accounts there are; hovering it says
		// which. Drawn last so it sits over the panel rather than under it.
		if (overDiscordMore(mouseX, mouseY)) {
			drawLabelTooltip(graphics, String.join(", ", discordMore), mouseX, mouseY);
		}

		// The pointer over anything a click copies, so the text reads as a
		// control rather than as a label that happens to react.
		if (overCopyable(mouseX, mouseY)) {
			graphics.requestCursor(CursorTypes.POINTING_HAND);
		}
	}

	/**
	 * How far down the panel reaches in a picture.
	 *
	 * <p>It ends below the model, and stretches to meet the cards when they
	 * run lower so the two columns finish level. The model is centred in
	 * whatever height that gives, which is handled by the widget itself.
	 */
	private int exportPanelBottom() {
		return MARGIN + profilePanelHeight();
	}

	/**
	 * How tall the profile panel is, which is also what the cards match.
	 *
	 * <p>On screen it fills the window. In a picture there is no history and
	 * no button row, so it only needs to reach below the model. Either way the
	 * cards stretch it further when their rows will not fit.
	 */
	private int profilePanelHeight() {
		return Math.max(naturalPanelHeight(), cardsHeight);
	}

	/**
	 * The panel's height before the cards have any say.
	 *
	 * <p>Kept separate from {@link #profilePanelHeight()} so the cards can be
	 * sized against it: measuring them against the combined height would feed
	 * their own height back in and let it climb frame after frame.
	 */
	private int naturalPanelHeight() {
		if (!exporting) {
			return height - MARGIN * 2;
		}
		// In a picture the panel holds only the name row and the model, and the
		// model shrinks to fit whatever the cards leave -- down to a floor that
		// keeps it recognisable. Using the on-screen model height here made the
		// panel tower over the cards.
		return skinTopLimit + EXPORT_SKIN_MIN + CARD_PADDING - MARGIN;
	}

	/**
	 * How much room the name history needs.
	 *
	 * <p>Only as many rows as the player actually has, capped at what fits.
	 * Reserving the full five regardless left a player with no previous names
	 * -- most of them -- with a band of empty space, and squeezed the model
	 * into what was left.
	 */
	private int historyBandHeight() {
		NameHistory history = SpogTiersClient.service().nameHistory(target);
		// "Loading..." and "No previous names" take a row just as a name does,
		// so the band is never shorter than one row under the heading -- the
		// empty state would otherwise end higher than a filled one.
		int rows = Math.max(1, history == null
				? 0
				: Math.min(history.previous().size(), HISTORY_ROWS));
		// The heading is always drawn, even with nothing under it.
		return font.lineHeight + 4 + rows * HISTORY_ROW_HEIGHT;
	}

	/**
	 * How tall the model is allowed to be before the history is asked to give
	 * way.
	 *
	 * <p>A player with a full history would otherwise squeeze the model to
	 * nothing. The list already scrolls, so it can hold fewer rows at once
	 * rather than taking the space from the model.
	 */
	private int modelFloor() {
		// Two thirds of the room between the name row and the button row, so
		// the model stays the thing the panel is mostly showing.
		int usable = (height - MARGIN - CARD_PADDING - 20 - 10) - skinTopLimit;
		return Math.clamp(usable * 2 / 3, 80, SKIN_HEIGHT);
	}

	/**
	 * Re-fits the model to the room the history leaves.
	 *
	 * <p>The history arrives after the screen opens, so the split cannot be
	 * settled once in {@code init}: a player whose names land late would keep
	 * the layout chosen when there were none.
	 */
	private void refitSkin() {
		if (skinWidget == null || exporting) {
			return;
		}

		// The history sits between the model and the button row. It is given
		// only the rows it has, but never so much that the model is squeezed
		// past its floor -- past that point the list scrolls instead.
		int cardBottom = height - MARGIN;
		int listBottom = cardBottom - CARD_PADDING - 20 - HISTORY_BUTTON_GAP;
		int wanted = Math.min(
				listBottom,
				Math.max(skinTopLimit + modelFloor() + 8,
						listBottom - historyBandHeight()));
		if (wanted == historyTop) {
			return;
		}

		historyTop = wanted;
		// The model gets the room above the history and no more, so the two
		// can never overlap however the floor works out.
		int band = historyTop - 8 - skinTopLimit;
		int fitted = Math.clamp(Math.min(band, SKIN_HEIGHT), 40, SKIN_HEIGHT);
		skinModelHeight = fitted;
		skinScreenY = skinTopLimit + Math.max(0, (band - fitted) / 2);
		skinWidget.setHeight(fitted);
		skinWidget.setY(skinScreenY);
		// The panel's width follows the window, so the model is re-centred in
		// it here rather than once at init.
		int modelWidth = skinWidgetWidth();
		skinWidget.setWidth(modelWidth);
		skinWidget.setX(MARGIN + (profileWidth() - modelWidth) / 2);
	}

	/** The profile card: face, name, region tag, skin model and buttons. */
	private void drawHeader(GuiGraphicsExtractor graphics) {
		Font font = this.font;

		int left = MARGIN;
		int top = MARGIN;
		// On screen the panel runs the full height of the window. In a picture
		// there is nothing below the model -- no history, no buttons -- so it
		// stops there instead, and matches the cards beside it when they are
		// taller.
		int bottom = MARGIN + profilePanelHeight();
		drawCardFrame(graphics, left, top, left + profileWidth(), bottom);
		panelBottom = bottom;

		// Laid out at the panel's full width and scaled down to whatever it
		// actually got, so the face, name and tag keep their proportions.
		float scale = panelScale();
		graphics.pose().pushMatrix();
		graphics.pose().translate(left, top);
		graphics.pose().scale(scale, scale);

		int innerX = CARD_PADDING;
		int y = CARD_PADDING;

		drawFace(graphics, innerX, y);

		int nameX = innerX + FACE_SIZE + 6;
		// +1 so the text sits optically centred against the face icon.
		int nameY = y + (FACE_SIZE - font.lineHeight) / 2 + 1;
		graphics.text(font, Component.literal(playerName), nameX, nameY, 0xFFFFFFFF);
		// In screen space, since that is where the mouse is: the header is
		// laid out at full width and drawn under a scale, so a box recorded in
		// panel coordinates would not line up with the pointer.
		nameCopyLeft = left + Math.round(nameX * scale);
		nameCopyTop = top + Math.round(nameY * scale);
		nameCopyRight = left + Math.round((nameX + font.width(playerName)) * scale);
		nameCopyBottom = top + Math.round((nameY + font.lineHeight) * scale);

		// Region reads as a small boxed tag beside the name, with our own grade
		// after it. tagX only advances when a tag was actually drawn, so a
		// player with no region gets the grade right after their name rather
		// than a gap where the region would have been.
		int tagX = nameX + font.width(playerName) + 5;
		String region = region();
		tagRegion = region;
		if (!region.isEmpty()) {
			tagX += drawTag(graphics, tagX, nameY - 3, region) + 4;
		}

		PlayerGrade grade = SpogTiersClient.config().extraTierlists
				? SpogTiersClient.service().grade(target)
				: null;
		gradeShown = grade != null && grade.isGraded();
		if (gradeShown) {
			drawGradeTag(graphics, tagX, nameY - 3, grade);
			gradeLabel = grade.tooltipLabel();
			gradeColor = grade.foreground();
		}

		// The linked Discord account, under the name rather than beside it:
		// the name row already carries the region and the grade, and a handle
		// is longer than either.
		//
		// Aligned to the face rather than to the name, so the mark starts on
		// the panel's own left edge and the two rows read as a block instead
		// of the lower one being indented under the upper.
		drawDiscord(graphics, innerX, y + FACE_SIZE + DISCORD_DROP);

		graphics.pose().popMatrix();

		// Outside the panel transform, because the model is a widget in screen
		// space rather than something drawn into the panel. Widgets render
		// after this method, so the embers land behind the player.
		// As above: the Particles setting is about the world, not this card.
		if (skinWidget != null && SpogTiersClient.config().extraTierlists) {
			aura.draw(graphics, grade, skinWidget.getX(), skinWidget.getY(),
					skinWidget.getWidth(), skinWidget.getHeight(), false);
		}
	}

	/**
	 * The player's linked Discord account, mark and name.
	 *
	 * <p>Drawn only once the answer is in and only when there is a name: most
	 * players have linked nothing, and the mark on its own would say a player
	 * has an account we simply cannot name.
	 */
	private void drawDiscord(GuiGraphicsExtractor graphics, int x, int y) {
		DiscordAccount account = discordAccount();
		if (account == null || account.label() == null) {
			// Cleared, not just left: most players have no Discord line, and a
			// box kept from one that did would keep copying their handle.
			discordCopyRight = discordCopyLeft;
			return;
		}
		Font font = this.font;
		int cursor = x;
		// White, not blurple: the glyph is coloured artwork and tinting it
		// would repaint it. The name beside it carries the colour instead.
		Component mark = ModeIcons.discord();
		graphics.text(font, mark, cursor, y, 0xFFFFFFFF);
		cursor += font.width(mark) + 3;

		// Handles can be longer than the card is wide -- Discord allows far
		// more characters than fit here -- so an over-long one is trimmed
		// rather than drawn past the edge. The full handle still copies.
		String name = clipToCard(account.label(), cursor);
		graphics.text(font, Component.literal(name), cursor, y,
				0xFF000000 | DiscordAccount.BLURPLE);
		// The handle only: the mark before it is decoration and the marker
		// after it belongs to the other accounts, so neither should copy.
		float nameScale = panelScale();
		discordCopyLeft = MARGIN + Math.round(cursor * nameScale);
		discordCopyTop = MARGIN + Math.round(y * nameScale);
		discordCopyRight = MARGIN + Math.round((cursor + font.width(name)) * nameScale);
		discordCopyBottom = MARGIN + Math.round((y + font.lineHeight) * nameScale);
		cursor += font.width(name);

		// The other accounts as a count rather than a list. Two handles side
		// by side read as one long name, and the second is the rarer case --
		// so it is a marker you can hover, not something in the way.
		List<String> extra = account.otherLabels();
		if (extra.isEmpty()) {
			// Cleared, not just left: the marker is absent for most players,
			// and a box kept from a profile that had one would go on
			// answering hovers on this one.
			discordMore = List.of();
			discordMoreRight = discordMoreLeft;
			return;
		}
		String marker = markerText(extra.size());
		// A long handle leaves no room for the marker beside it, and drawn
		// anyway it runs past the card's edge. It drops to its own row under
		// the handle instead, which is why the header reserves a second row
		// for exactly this case.
		int markerX = cursor;
		int markerY = y;
		if (wrapsMarker(account.label(), extra.size())) {
			markerX = x;
			markerY = y + font.lineHeight + 1;
		}
		graphics.text(font, Component.literal(marker), markerX, markerY, MUTED_COLOR);

		// In screen space, since that is where the pointer is: the header is
		// laid out at full width and drawn under a scale.
		float scale = panelScale();
		discordMoreLeft = MARGIN + Math.round(markerX * scale);
		discordMoreTop = MARGIN + Math.round(markerY * scale);
		discordMoreRight = MARGIN + Math.round((markerX + font.width(marker)) * scale);
		discordMoreBottom = MARGIN + Math.round((markerY + font.lineHeight) * scale);
		discordMore = extra;
	}

	/**
	 * The account to draw, or null while it is unknown, absent or turned off.
	 *
	 * <p>Everything about the Discord line asks here -- the drawing, the room
	 * the header reserves for it, the box a click copies -- so the setting is
	 * honoured everywhere by being honoured once. Turning it off also stops
	 * the lookup, since the request is made from the read below rather than
	 * ahead of it.
	 */
	private DiscordAccount discordAccount() {
		if (target == null || !SpogTiersClient.config().showDiscord) {
			return null;
		}
		return SpogTiersClient.service().discord(target);
	}

	/** The extra height the header needs for a Discord line, or zero. */
	private int discordRoom() {
		DiscordAccount account = discordAccount();
		// The same test the drawing uses, or the header would reserve a row
		// nothing lands in, or draw into one it never reserved.
		if (account == null || account.label() == null) {
			return 0;
		}
		// And a second row when the marker will not fit beside the handle, for
		// the same reason: the drawing wraps it there, so the space has to be
		// reserved here or it lands on top of the model.
		int rows = wrapsMarker(account.label(), account.otherLabels().size()) ? 2 : 1;
		return DISCORD_ROW + (rows - 1) * (font.lineHeight + 1);
	}

	/** The marker drawn after a handle when other accounts are linked. */
	private static String markerText(int others) {
		return " [" + others + " other]";
	}

	/**
	 * Whether the marker has to drop to its own row under the handle.
	 *
	 * <p>Measured in panel space, where the width is the constant the header
	 * is laid out at rather than whatever it was scaled to -- the drawing uses
	 * the same space, so the two agree at every GUI scale. The mark and its
	 * gap sit left of the handle, and the padding on the right is the card's
	 * own, so the text stops where the card's content does.
	 */
	private boolean wrapsMarker(String name, int others) {
		if (others <= 0) {
			return false;
		}
		int markStart = CARD_PADDING + font.width(ModeIcons.discord()) + 3;
		int used = markStart + font.width(clipToCard(name, markStart))
				+ font.width(markerText(others));
		return used > PROFILE_WIDTH - CARD_PADDING;
	}

	/**
	 * A handle trimmed to what fits between {@code x} and the card's edge.
	 *
	 * <p>Returned whole when it already fits, which is the ordinary case.
	 * Panel space again, so this agrees with the drawing at any GUI scale.
	 */
	private String clipToCard(String text, int x) {
		int room = PROFILE_WIDTH - CARD_PADDING - x;
		if (text == null || font.width(text) <= room) {
			return text;
		}
		return font.plainSubstrByWidth(text, room - font.width("...")) + "...";
	}

	/**
	 * Past names under the model, newest first, each with how long ago it was
	 * taken.
	 *
	 * <p>Only names before the current one are listed -- the current one is
	 * already at the top of the card. Accounts rename a lot (some have dozens),
	 * so the list is clipped to its band and scrolls.
	 */
	private void drawNameHistory(GuiGraphicsExtractor graphics) {
		Font font = this.font;
		// Same treatment as the header: laid out full width, drawn scaled.
		float scale = panelScale();
		graphics.pose().pushMatrix();
		graphics.pose().translate(MARGIN, historyTop);
		graphics.pose().scale(scale, scale);

		int x = CARD_PADDING;
		// The scrollbar track sits just outside this edge, so the rows stop
		// short of it rather than running right up to it -- at a small panel
		// width the two were touching.
		int right = PROFILE_WIDTH - CARD_PADDING - SCROLLBAR_GUTTER;
		int y = 0;

		graphics.text(font, Component.literal("Name history"), x, y, LABEL_COLOR);
		y += font.lineHeight + 4;

		// Both of these leave early, so the panel transform has to come off
		// first: leaking it left every tooltip drawn afterwards offset by the
		// panel's own scale and origin.
		NameHistory history = SpogTiersClient.service().nameHistory(target);
		if (history == null) {
			graphics.text(font, Component.literal("Loading..."), x, y, MUTED_COLOR);
			historyMaxScroll = 0;
			graphics.pose().popMatrix();
			return;
		}

		List<NameHistory.Entry> previous = history.previous();
		if (previous.isEmpty()) {
			graphics.text(font, Component.literal("No previous names"), x, y, MUTED_COLOR);
			historyMaxScroll = 0;
			graphics.pose().popMatrix();
			return;
		}

		// y is panel-local here and historyBottom is a screen coordinate, so
		// the band's depth is measured from historyTop. Mixing the two made
		// visible far too large, which zeroed the scroll range and left the
		// list stuck.
		int bandDepth = Math.round((historyBottom - historyTop) / panelScale()) - y;
		int visible = Math.max(1, bandDepth / HISTORY_ROW_HEIGHT);
		historyMaxScroll = Math.max(0, previous.size() - visible);
		historyScroll = Math.clamp(historyScroll, 0, historyMaxScroll);

		// Clipped so a long history cannot spill over the close button.
		// The scissor is in the transformed space too, so the band's bottom is
		// expressed relative to the origin this method translated to.
		graphics.enableScissor(x, y, right,
				Math.round((historyBottom - historyTop) / panelScale()));
		for (int i = 0; i < visible && i + historyScroll < previous.size(); i++) {
			NameHistory.Entry entry = previous.get(i + historyScroll);
			int rowY = y + i * HISTORY_ROW_HEIGHT;

			String ago = entry.isDated() ? timeAgo(entry.changedAt()) : "";
			int agoWidth = ago.isEmpty() ? 0 : font.width(ago);

			graphics.text(font, Component.literal(
							trim(entry.name(), right - x - agoWidth - 6)),
					x, rowY, 0xFFD5DCE5);
			if (!ago.isEmpty()) {
				graphics.text(font, Component.literal(ago), right - agoWidth, rowY, MUTED_COLOR);
			}
		}
		graphics.disableScissor();

		if (historyMaxScroll > 0) {
			int trackHeight = visible * HISTORY_ROW_HEIGHT;
			int thumbHeight = Math.max(8, trackHeight * visible / previous.size());
			int thumbY = y + (trackHeight - thumbHeight) * historyScroll / historyMaxScroll;
			graphics.fill(right + 2, y, right + 4, y + trackHeight, 0x40202A38);
			graphics.fill(right + 2, thumbY, right + 4, thumbY + thumbHeight, 0x90727F8F);
		}

		graphics.pose().popMatrix();
	}

	/**
	 * Scrolls the name history when the cursor is over it.
	 *
	 * <p>Scoped to the band, and only when there is something to scroll, so the
	 * wheel keeps its usual meaning everywhere else on the screen.
	 */
	/** Whether a point is inside a recorded box, right and bottom excluded. */
	private static boolean within(double x, double y,
			int left, int top, int right, int bottom) {
		// A zero-width box is one that was never drawn this frame -- the
		// Discord line is absent for most players -- and must never match.
		return right > left && x >= left && x < right && y >= top && y < bottom;
	}

	/** Whether the pointer is over the "other accounts" marker. */
	private boolean overDiscordMore(int mouseX, int mouseY) {
		return !discordMore.isEmpty() && within(mouseX, mouseY,
				discordMoreLeft, discordMoreTop, discordMoreRight, discordMoreBottom);
	}

	/** Whether the pointer is over something a click would copy. */
	private boolean overCopyable(int mouseX, int mouseY) {
		return within(mouseX, mouseY, nameCopyLeft, nameCopyTop,
						nameCopyRight, nameCopyBottom)
				|| within(mouseX, mouseY, discordCopyLeft, discordCopyTop,
						discordCopyRight, discordCopyBottom);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		// The name and the linked handle copy on click. Both are things people
		// retype into a search or a DM, and retyping a Minecraft name is where
		// a typo costs a failed lookup.
		if (within(event.x(), event.y(), nameCopyLeft, nameCopyTop,
				nameCopyRight, nameCopyBottom)) {
			return copyToClipboard(playerName);
		}
		if (within(event.x(), event.y(), discordCopyLeft, discordCopyTop,
				discordCopyRight, discordCopyBottom)) {
			DiscordAccount account = discordAccount();
			return account != null && copyToClipboard(account.label());
		}
		return super.mouseClicked(event, doubled);
	}

	/**
	 * Puts one string on the clipboard.
	 *
	 * <p>The click plays the usual UI sound and nothing else is shown: the
	 * pointer over the text already says it is clickable.
	 */
	private boolean copyToClipboard(String text) {
		if (text == null || text.isBlank()) {
			return false;
		}
		Minecraft.getInstance().keyboardHandler.setClipboard(text);
		Minecraft.getInstance().getSoundManager().play(
				SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
		return true;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
		boolean overHistory = mouseX >= MARGIN && mouseX <= MARGIN + profileWidth()
				&& mouseY >= historyTop && mouseY <= historyBottom;
		if (overHistory && historyMaxScroll > 0) {
			historyScroll = Math.clamp(
					historyScroll - (int) Math.signum(deltaY), 0, historyMaxScroll);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
	}

	/** Truncates to fit, so a long name cannot run into its date. */
	private String trim(String text, int max) {
		String out = text;
		while (font.width(out) > max && out.length() > 1) {
			out = out.substring(0, out.length() - 1);
		}
		return out;
	}

	/**
	 * A compact "how long ago", e.g. {@code 3y} or {@code 5mo}.
	 *
	 * <p>Kept to one unit and a couple of characters: the column is narrow, and
	 * the point is the rough age rather than an exact figure.
	 */
	private static String timeAgo(long epochSeconds) {
		long seconds = Instant.now().getEpochSecond() - epochSeconds;
		if (seconds < 60) {
			return "just now";
		}
		long minutes = seconds / 60;
		if (minutes < 60) {
			return minutes + "m ago";
		}
		long hours = minutes / 60;
		if (hours < 24) {
			return hours + "h ago";
		}
		long days = hours / 24;
		if (days < 31) {
			return days + "d ago";
		}
		long months = days / 30;
		if (months < 12) {
			return months + "mo ago";
		}
		return (days / 365) + "y ago";
	}

	/**
	 * A rank badge, drawn in the region tag's style.
	 *
	 * <p>Grey for the rest of the top 500, then bronze, silver and gold for
	 * third, second and first.
	 *
	 * @return the width drawn, or 0 when the rank does not earn a badge
	 */
	private int drawRankTag(GuiGraphicsExtractor graphics, int x, int y, int rank) {
		if (rank < 1 || rank > TierService.TOP_RANK_LIMIT) {
			return 0;
		}
		Font font = this.font;
		String text = "#" + rank;
		int boxWidth = font.width(text) + 8;
		int boxHeight = font.lineHeight + 5;

		int foreground = rankForeground(rank);
		int background = rankBackground(rank);
		int border = (0xB0 << 24) | (foreground & 0xFFFFFF);

		graphics.fill(x, y, x + boxWidth, y + boxHeight, background);
		graphics.fill(x, y, x + boxWidth, y + 1, border);
		graphics.fill(x, y + boxHeight - 1, x + boxWidth, y + boxHeight, border);
		graphics.fill(x, y, x + 1, y + boxHeight, border);
		graphics.fill(x + boxWidth - 1, y, x + boxWidth, y + boxHeight, border);

		graphics.text(font, Component.literal(text), x + 4, y + 3, foreground);
		return boxWidth;
	}

	private static int rankForeground(int rank) {
		return switch (rank) {
			case 1 -> 0xFFF2C74B;
			case 2 -> 0xFFCBD5E1;
			case 3 -> 0xFFCE8C4A;
			default -> 0xFFB9C4D0;
		};
	}

	private static int rankBackground(int rank) {
		return switch (rank) {
			case 1 -> 0xE0413412;
			case 2 -> 0xE02E3440;
			case 3 -> 0xE03A2614;
			default -> 0xE0232B36;
		};
	}

	/** A boxed region label, coloured with MCTiers' region palette (its
	 * {@code --<region>} / {@code --<region>-foreground} CSS variables).
	 */
	private int drawTag(GuiGraphicsExtractor graphics, int x, int y, String text) {
		Font font = this.font;
		int boxWidth = font.width(text) + 8;
		int boxHeight = font.lineHeight + 5;

		// The tag is drawn inside the panel's transform, so these are panel
		// coordinates; the hover test runs in screen space, so they are
		// converted back here rather than there.
		float scale = panelScale();
		tagLeft = MARGIN + Math.round(x * scale);
		tagTop = MARGIN + Math.round(y * scale);
		tagRight = MARGIN + Math.round((x + boxWidth) * scale);
		tagBottom = MARGIN + Math.round((y + boxHeight) * scale);

		int foreground = regionForeground(text);
		int background = regionBackground(text);
		int border = (0xB0 << 24) | (foreground & 0xFFFFFF);

		graphics.fill(x, y, x + boxWidth, y + boxHeight, background);
		graphics.fill(x, y, x + boxWidth, y + 1, border);
		graphics.fill(x, y + boxHeight - 1, x + boxWidth, y + boxHeight, border);
		graphics.fill(x, y, x + 1, y + boxHeight, border);
		graphics.fill(x + boxWidth - 1, y, x + boxWidth, y + boxHeight, border);

		graphics.text(font, Component.literal(text), x + 4, y + 3, foreground);
		return boxWidth;
	}

	/**
	 * The player's Door SMP grade, in the region tag's style.
	 *
	 * <p>Our own list, so it sits next to the region rather than in a card of
	 * its own: it is one letter with no gamemodes behind it.
	 *
	 * @return the width drawn, or 0 when there is no grade to show
	 */
	private int drawGradeTag(GuiGraphicsExtractor graphics, int x, int y, PlayerGrade grade) {
		Font font = this.font;
		String text = grade.label();
		// The door sits left of the label, as it does on a nametag, so our tag
		// is recognisable as the same thing in both places.
		Component icon = ModeIcons.door();
		int iconWidth = font.width(icon) + 2;
		int boxWidth = iconWidth + font.width(text) + 8;
		int boxHeight = font.lineHeight + 5;

		// Same panel-to-screen conversion as the region tag: this is drawn
		// inside the panel transform, but the hover test runs outside it.
		float scale = panelScale();
		gradeLeft = MARGIN + Math.round(x * scale);
		gradeTop = MARGIN + Math.round(y * scale);
		gradeRight = MARGIN + Math.round((x + boxWidth) * scale);
		gradeBottom = MARGIN + Math.round((y + boxHeight) * scale);

		int foreground = grade.foreground();
		int background = grade.background();
		int border = (0xB0 << 24) | (foreground & 0xFFFFFF);

		graphics.fill(x, y, x + boxWidth, y + boxHeight, background);
		graphics.fill(x, y, x + boxWidth, y + 1, border);
		graphics.fill(x, y + boxHeight - 1, x + boxWidth, y + boxHeight, border);
		graphics.fill(x, y, x + 1, y + boxHeight, border);
		graphics.fill(x + boxWidth - 1, y, x + boxWidth, y + boxHeight, border);

		// White so the glyph keeps its own colours: tinting it with the tag's
		// foreground would flood the artwork with a single hue.
		// Nudged up and left by a pixel: the door's artwork sits low and right
		// in its cell, so drawn flush it reads as off-centre against the label.
		graphics.text(font, icon, x + 3, y + 2, 0xFFFFFFFF);
		graphics.text(font, Component.literal(text), x + 4 + iconWidth, y + 3, foreground);
		return boxWidth;
	}

	private static int regionForeground(String region) {
		return switch (region) {
			case "NA" -> 0xFFD95C6A;
			case "EU" -> 0xFF89F19C;
			case "AS" -> 0xFFAF7F91;
			case "AU", "OCE", "OC" -> 0xFFD5AD80;
			case "SA" -> 0xFF5DCCDC;
			case "ME" -> 0xFFE0B36A;
			case "AF" -> 0xFF9FD18A;
			default -> 0xFFB9C4D0;
		};
	}

	private static int regionBackground(String region) {
		return switch (region) {
			case "NA" -> 0xE0442228;
			case "EU" -> 0xE01C3E20;
			case "AS" -> 0xE0422C3F;
			case "AU", "OCE", "OC" -> 0xE0392E27;
			case "SA" -> 0xE0193845;
			case "ME" -> 0xE0433A22;
			case "AF" -> 0xE0253A1E;
			default -> 0xE0232B36;
		};
	}

	private void drawCardFrame(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom) {
		graphics.fill(left, top, right, bottom, CARD_FILL);
		graphics.fill(left, top, right, top + 1, CARD_BORDER);
		graphics.fill(left, bottom - 1, right, bottom, CARD_BORDER);
		graphics.fill(left, top, left + 1, bottom, CARD_BORDER);
		graphics.fill(right - 1, top, right, bottom, CARD_BORDER);
	}

	/** Draws the head, then the hat layer, scaled up from the 64x64 skin sheet. */
	private void drawFace(GuiGraphicsExtractor graphics, int x, int y) {
		PlayerSkin resolved = skin == null ? null : skin.get();
		if (resolved == null) {
			return;
		}
		graphics.blit(RenderPipelines.GUI_TEXTURED, resolved.body().texturePath(),
				x, y, 8.0f, 8.0f, FACE_SIZE, FACE_SIZE, 8, 8, 64, 64);
		graphics.blit(RenderPipelines.GUI_TEXTURED, resolved.body().texturePath(),
				x, y, 40.0f, 8.0f, FACE_SIZE, FACE_SIZE, 8, 8, 64, 64);
	}

	/**
	 * The player's region code, voted on across every list that reports one.
	 *
	 * <p>See {@link Regions} for why this is a vote rather than a preference
	 * order.
	 */
	private String region() {
		return Regions.resolve(SpogTiersClient.cache().allLists(target));
	}

	/** One fixed-size card per ranked list, arranged in a balanced grid. */
	private void drawCards(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		Font font = this.font;
		Map<TierList, PlayerTiers> all = SpogTiersClient.cache().allLists(target);

		List<Card> cards = new ArrayList<>();
		for (TierList list : TierList.values()) {
			if (!SpogTiersClient.config().isEnabled(list)) {
				continue;
			}
			PlayerTiers tiers = all.get(list);
			if (tiers == null) {
				continue;
			}
			List<Row> rows = collectRows(tiers);
			if (!rows.isEmpty()) {
				cards.add(new Card(list, rows));
			}
		}

		int contentLeft = MARGIN + profileWidth() + MARGIN;
		int contentWidth = Math.max(160, width - contentLeft - MARGIN);
		int contentTop = MARGIN;
		int contentBottom = height - MARGIN;

		if (cards.isEmpty()) {
			String message = SpogTiersClient.cache().isPending(target)
					? "Loading rankings..."
					: playerName + " is unranked";
			graphics.text(font, Component.literal(message),
					contentLeft + (contentWidth - font.width(message)) / 2,
					contentTop + 40,
					MUTED_COLOR);
			return;
		}

		// Prefer a balanced grid over a full first row: 4 cards read better as
		// 2x2 than 3+1, and 3 stay on one row.
		int perRow = switch (cards.size()) {
			case 1 -> 1;
			case 2 -> 2;
			case 3 -> 3;
			default -> (cards.size() + 1) / 2;
		};
		int rowCount = (cards.size() + perRow - 1) / perRow;

		// On screen every card is the same size, whichever lists loaded and
		// however many modes they rank: a floor of CARD_ROWS and a share of the
		// window keep the grid from resizing as data lands. Tightening any of
		// that is a picture-only concern -- a picture is taken once, so nothing
		// is going to shift under the reader.
		int[] rowHeights = new int[rowCount];
		if (exporting) {
			// Each row only as deep as its own longest card, so a row of short
			// lists is not padded out to match a row carrying CatPVP's fourteen
			// modes.
			for (int row = 0; row < rowCount; row++) {
				int rowsNeeded = 0;
				for (int column = 0; column < perRow; column++) {
					int index = row * perRow + column;
					if (index < cards.size()) {
						rowsNeeded = Math.max(rowsNeeded, cards.get(index).rows().size());
					}
				}
				rowHeights[row] = CARD_PADDING * 2 + 18 + rowsNeeded * ROW_HEIGHT;
			}
		} else {
			int rowsNeeded = CARD_ROWS;
			for (Card card : cards) {
				rowsNeeded = Math.max(rowsNeeded, card.rows().size());
			}
			// Sized as if the grid were two rows deep, so one row of cards is
			// as tall as one row of a 2x2 grid rather than stretching to fill
			// the screen.
			int available = (contentBottom - contentTop) - CARD_GAP;
			int uniform = Math.max(
					CARD_PADDING * 2 + 18 + rowsNeeded * ROW_HEIGHT,
					available / 2);
			Arrays.fill(rowHeights, uniform);
		}

		int naturalHeight = 0;
		for (int rowHeight : rowHeights) {
			naturalHeight += rowHeight;
		}
		naturalHeight += (rowCount - 1) * CARD_GAP;

		// A card holding one or two modes is mostly empty across, and in a
		// picture that emptiness is what the eye lands on. Narrow it to what
		// its contents actually need -- the header, and the widest label
		// against its tier -- but only for genuinely short cards, and only in
		// a picture: on screen the width is fixed like the height.
		int cardWidth = CARD_WIDTH;
		if (exporting) {
			int deepestRow = 0;
			for (Card card : cards) {
				deepestRow = Math.max(deepestRow, card.rows().size());
			}
			if (deepestRow <= NARROW_ROW_LIMIT) {
				int needed = 0;
				for (Card card : cards) {
					needed = Math.max(needed, cardContentWidth(card));
				}
				cardWidth = Math.clamp(needed, CARD_WIDTH_MIN, CARD_WIDTH);
			}
		}

		int blockWidth = perRow * cardWidth + (perRow - 1) * CARD_GAP;


		// Scale only to fit; never blow the cards up when there is spare room.
		// The width is what usually binds, so this is settled before the rows
		// are grown -- growing first and scaling after would simply shrink the
		// extra height straight back out again.
		int availableHeight = contentBottom - contentTop;
		float scale = Math.min(1.0f, Math.min(
				(float) contentWidth / blockWidth,
				(float) availableHeight / naturalHeight));

		// Narrowing a card only to have the fit-scale blow it back up defeats
		// the point: the block shrinks, the spare width becomes headroom, and
		// the cards come out bigger than the full-width ones. Hold the scale
		// where the full-width block would have put it.
		if (cardWidth < CARD_WIDTH) {
			int fullWidth = perRow * CARD_WIDTH + (perRow - 1) * CARD_GAP;
			scale = Math.min(scale, Math.min(1.0f, (float) contentWidth / fullWidth));
		}

		// A player with a handful of tiers across many lists leaves the grid
		// far shorter than the profile panel, which reads as unfinished in a
		// picture. The spare height is handed back to the rows in proportion so
		// they grow together and the grid finishes level with the panel. On
		// screen the cards keep their fixed size instead.
		//
		// Measured in unscaled units, since that is what the rows are drawn in:
		// the target is what the panel is worth once the scale is undone.
		int target = exporting ? Math.round(naturalPanelHeight() / scale) : 0;
		if (naturalHeight < target) {
			int spare = target - naturalHeight;
			int contentOnly = naturalHeight - (rowCount - 1) * CARD_GAP;
			int given = 0;
			for (int row = 0; row < rowCount; row++) {
				// The last row takes the rounding remainder, so the total lands
				// exactly on the target rather than a pixel or two short.
				int share = row == rowCount - 1
						? spare - given
						: spare * rowHeights[row] / contentOnly;
				rowHeights[row] += share;
				given += share;
			}
			naturalHeight = target;
		}

		int blockHeight = naturalHeight;

		// Row tops depend on the heights, so they are worked out once those
		// have settled.
		int[] rowTops = new int[rowCount];
		for (int row = 1; row < rowCount; row++) {
			rowTops[row] = rowTops[row - 1] + rowHeights[row - 1] + CARD_GAP;
		}

		// Tooltips belonging to a card are drawn at the same size the card is,
		// so a shrunken grid does not carry full-size tooltips over it.
		cardScale = scale;

		// What the panel has to match: the height the cards are actually drawn
		// at, which is the scaled one. Matching the unscaled height made the
		// panel tower over a grid that had been shrunk to fit.
		cardsHeight = Math.round(blockHeight * scale);

		int scaledWidth = Math.round(blockWidth * scale);
		// Centred in the space beside the panel on screen. In a picture there
		// is no window to sit in the middle of, so the block is pulled in to a
		// card gap from the panel and the empty right-hand side is cropped
		// away with it.
		int originX = contentLeft + (contentWidth - scaledWidth) / 2;
		if (exporting) {
			originX = Math.min(originX, MARGIN + profileWidth() + CARD_GAP);
		}
		// Centred in the height beside the panel on screen, which is what a
		// single row of cards needs: at the top it sits against the panel's
		// header with the whole lower half empty. A two-row grid fills that
		// height anyway, so this only shows on the short case.
		//
		// In a picture the grid is grown to the panel's height already, so
		// there is nothing to centre and the top is where it belongs.
		int originY = exporting
				? contentTop
				: contentTop + Math.max(0,
						(availableHeight - Math.round(blockHeight * scale)) / 2);

		// Remembered so an export can crop to the cards rather than to the
		// whole window.
		cardsRight = originX + scaledWidth;
		cardsBottom = originY + Math.round(blockHeight * scale);

		graphics.pose().pushMatrix();
		graphics.pose().translate(originX, originY);
		graphics.pose().scale(scale, scale);

		for (int index = 0; index < cards.size(); index++) {
			int row = index / perRow;
			int column = index % perRow;

			int inThisRow = Math.min(perRow, cards.size() - row * perRow);
			int rowWidth = inThisRow * cardWidth + (inThisRow - 1) * CARD_GAP;
			int rowLeft = (blockWidth - rowWidth) / 2;

			int x = rowLeft + column * (cardWidth + CARD_GAP);
			int y = rowTops[row];

			// Mouse mapped into the scaled card space, so hit-testing matches
			// what is actually drawn.
			float localX = (mouseX - originX) / scale;
			float localY = (mouseY - originY) / scale;
			drawCard(graphics, cards.get(index), x, y, cardWidth, rowHeights[row],
					localX, localY);
		}

		graphics.pose().popMatrix();
	}

	private void drawCard(GuiGraphicsExtractor graphics, Card card, int x, int y,
			int cardWidth, int cardHeight, float localX, float localY) {
		Font font = this.font;

		drawCardFrame(graphics, x, y, x + cardWidth, y + cardHeight);

		int textY = y + CARD_PADDING;

		// The player's standing on this list, which belongs beside the list it
		// came from rather than beside their name.
		SpogTiersClient.service().requestTopRanks(card.list(), null);
		int listRank = SpogTiersClient.service().topRank(target, card.list(), null);
		boolean showRank = listRank > 0 && listRank <= TierService.TOP_RANK_LIMIT;

		// Logo, title and badge are centred together as one unit.
		String title = card.list().displayName();
		int badgeWidth = showRank ? font.width("#" + listRank) + 8 + 4 : 0;
		int headerWidth = LOGO_SIZE + 4 + font.width(title) + badgeWidth;
		int headerX = x + (cardWidth - headerWidth) / 2;

		Identifier logo = Identifier.fromNamespaceAndPath(
				SpogTiers.MOD_ID, card.list().logoPath());
		graphics.blit(RenderPipelines.GUI_TEXTURED, logo,
				headerX, textY + (font.lineHeight - LOGO_SIZE) / 2 - 2,
				0.0f, 0.0f, LOGO_SIZE, LOGO_SIZE, 64, 64, 64, 64);
		graphics.text(font, Component.literal(title),
				headerX + LOGO_SIZE + 4, textY, 0xFFFFFFFF);
		// Logo and name together are the hover target, which is the whole
		// header block rather than either piece alone.
		if (localX >= headerX && localX <= headerX + LOGO_SIZE + 4 + font.width(title)
				&& localY >= textY - 3 && localY <= textY + font.lineHeight + 2) {
			headerHover = card.list();
		}

		if (showRank) {
			drawRankTag(graphics, headerX + LOGO_SIZE + 4 + font.width(title) + 4,
					textY - 3, listRank);
		}

		textY += font.lineHeight + 4;
		graphics.fill(x + CARD_PADDING, textY, x + cardWidth - CARD_PADDING, textY + 1, 0x28FFFFFF);
		// Extra gap so the first gamemode does not crowd the separator.
		textY += 7;

		int textX = x + CARD_PADDING;
		int widestValue = 0;
		int widestPeak = 0;
		for (Row row : card.rows()) {
			// Measure the bare label: the R on a retired tier hangs into the
			// gap on the left, so the tier codes stay aligned down the column.
			// Only real tier codes set the column width. A placement run is
			// wider than any of them, and letting it in dragged the whole
			// column left to line up with the run instead.
			if (!row.placing()) {
				widestValue = Math.max(widestValue, font.width(row.tier().bareLabel()));
			}
			if (row.showsPeak()) {
				widestPeak = Math.max(widestPeak, font.width(row.peak().label()));
			}
		}
		int valueX = x + cardWidth - CARD_PADDING - widestValue;

		for (Row row : card.rows()) {
			// Whole row is the hit target, not just the label.
			if (localX >= x && localX <= x + cardWidth
					&& localY >= textY - 2 && localY < textY + ROW_HEIGHT - 2) {
				hover = new Hover(card.list(), row);
			}

			int labelX = textX;
			// Only blit artwork the list actually ships: a provider can rank a
			// mode we have no icon for, and a missing texture draws the magenta
			// chequer across the row.
			if (row.iconKey() != null && hasIcon(card.list(), row.iconKey())) {
				Identifier icon = Identifier.fromNamespaceAndPath(
						SpogTiers.MOD_ID, card.list().modeIconPath(row.iconKey()));
				graphics.blit(RenderPipelines.GUI_TEXTURED, icon,
						textX, textY - 2, 0.0f, 0.0f,
						MODE_ICON, MODE_ICON, 64, 64, 64, 64);
				labelX += MODE_ICON + 3;
			}
			graphics.text(font, Component.literal(row.label()), labelX, textY, row.accent());

			// Everything left of the tier column stacks leftward from here, so
			// a row carrying both a peak and a promotion run lays them out end
			// to end instead of drawing one over the other.
			int extrasRight = valueX - 5;

			// Peak sits to the left of the current tier, struck through to read
			// as "used to be".
			if (row.showsPeak()) {
				String peakLabel = row.peak().label();
				int peakX = extrasRight - widestPeak + (widestPeak - font.width(peakLabel));
				int peakColor = fade(row.peak().color());
				graphics.text(font, Component.literal(peakLabel), peakX, textY, peakColor);
				graphics.fill(peakX, textY + font.lineHeight / 2,
						peakX + font.width(peakLabel), textY + font.lineHeight / 2 + 1, peakColor);
				// Reserve the whole peak column, not just this label, so runs
				// stay in one line down the card rather than jittering with
				// each row's peak width.
				extrasRight -= widestPeak + 5;
			}

			// The value column ends here, so anything drawn in it is aligned to
			// this edge rather than to the tier column's own left edge.
			int valueRight = x + cardWidth - CARD_PADDING;

			// While placing there is no tier to show, so the run goes in its
			// place -- that is the useful fact about the row.
			if (row.placing()) {
				String run = row.run();
				graphics.text(font, Component.literal(run),
						valueRight - font.width(run), textY, PLACEMENT_COLOR);
				textY += ROW_HEIGHT;
				continue;
			}

			// Draw from the bare label's slot so the R extends leftward and the
			// tier codes themselves stay in one column.
			String label = row.tier().label();
			int labelOffset = font.width(label) - font.width(row.tier().bareLabel());
			graphics.text(font, Component.literal(label),
					valueX - labelOffset, textY, row.tier().color());

			// A test run sits in brackets to the left of the tier it is trying
			// to leave -- and to the left of the peak as well when the row has
			// one, since both want the same space.
			if (!row.run().isEmpty() && !row.placing()) {
				String progress = "(" + row.run() + ")";
				// Without a peak the run keeps hugging the tier column, which
				// is where it has always sat; the retired R is allowed to
				// overhang into the same gap.
				int runRight = row.showsPeak() ? extrasRight : valueX - labelOffset - 4;
				graphics.text(font, Component.literal(progress),
						runRight - font.width(progress), textY, PLACEMENT_COLOR);
			}
			textY += ROW_HEIGHT;
		}
	}

	/**
	 * How wide a card needs to be for its own contents.
	 *
	 * <p>The wider of its header -- logo, name and rank badge -- and its widest
	 * row, where a row is an icon, a label, and the tier hard against the right
	 * edge with a gap between the two.
	 */
	private int cardContentWidth(Card card) {
		Font font = this.font;

		int listRank = SpogTiersClient.service().topRank(target, card.list(), null);
		boolean showRank = listRank > 0 && listRank <= TierService.TOP_RANK_LIMIT;
		int badgeWidth = showRank ? font.width("#" + listRank) + 8 + 4 : 0;
		int widest = LOGO_SIZE + 4 + font.width(card.list().displayName()) + badgeWidth;

		for (Row row : card.rows()) {
			int label = MODE_ICON + 3 + font.width(row.label());
			int value = row.placing()
					? font.width(row.run())
					: font.width(row.tier().label());
			if (row.showsPeak()) {
				value += font.width(row.peak().label()) + 5;
			}
			if (!row.run().isEmpty() && !row.placing()) {
				value += font.width("(" + row.run() + ")") + 4;
			}
			// A comfortable gap between the label and the tier, so the two
			// never read as one run of text.
			widest = Math.max(widest, label + 12 + value);
		}
		return widest + CARD_PADDING * 2;
	}

	/** Whether placement rows are wanted at all. */
	private static boolean showPlacements() {
		SpogTiersConfig config = SpogTiersClient.config();
		return config == null || config.showPlacements;
	}

	/**
	 * Copies the profile to the clipboard as a picture.
	 *
	 * <p>Runs over two frames: this one marks the screen as exporting, and the
	 * next renders it stripped of everything that is not the profile and is
	 * read back. The flag is cleared once the capture has been taken, whether
	 * or not it worked.
	 */
	private void beginExport() {
		if (exporting) {
			return;
		}

		// Bounds cannot be measured yet: the clean frame has not been drawn,
		// so the panel and the cards are still at their on-screen sizes. They
		// are taken from that frame instead, once both have shrunk.
		// Only arm it here. The click arrives partway through a frame that has
		// already been drawn with the buttons on it, so capturing now would
		// grab that frame; the capture is taken at the end of the next one.
		exporting = true;
		captureQueued = true;
		cleanFrameDrawn = false;
	}

	/**
	 * Takes the capture, called at the end of the stripped-down frame.
	 */
	private void finishExport() {
		captureQueued = false;
		cleanFrameDrawn = false;
		ProfileExport.copy(exportLeft, exportTop,
				exportRight - exportLeft, exportBottom - exportTop,
				() -> exporting = false);
	}

	/**
	 * Throws away everything cached for this player and asks again.
	 *
	 * <p>The name history and the leaderboard standings are session caches
	 * rather than per-player ones, so they are left alone; the tiers are what
	 * a refresh is for.
	 */
	private void refresh() {
		SpogTiersClient.service().refresh(target);
	}

	/** True when the list declares this mode, and so ships artwork for it. */
	private static boolean hasIcon(TierList list, String modeKey) {
		Gamemode mode = Gamemode.byKey(modeKey);
		return mode != null && list.gamemodes().contains(mode);
	}

	/**
	 * Known gamemodes in enum order, then anything the provider added, with the
	 * user's chosen ordering applied.
	 */
	private List<Row> collectRows(PlayerTiers tiers) {
		List<Row> rows = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();

		for (Gamemode mode : Gamemode.values()) {
			Tier tier = tiers.get(mode);
			// A player mid-placement holds no tier yet, but the run itself is
			// worth a row -- unless the user has turned those off.
			if (tier.isRanked()
					|| (showPlacements() && tiers.detail(mode.displayName()).isPlacing())) {
				TierDetail detail = tiers.detail(mode.displayName());
				rows.add(new Row(mode.displayName(), tier, mode.accent(), mode.key(),
						detail.peak(), detail.attainedSeconds(),
						detail.runLabel(), detail.isPlacing(), detail.placementGames()));
				seen.add(mode.displayName());
			}
		}
		for (Map.Entry<String, Tier> entry : tiers.unknown().entrySet()) {
			if (entry.getValue().isRanked() && seen.add(entry.getKey())) {
				TierDetail detail = tiers.detail(entry.getKey());
				rows.add(new Row(entry.getKey(), entry.getValue(), LABEL_COLOR, null,
						detail.peak(), detail.attainedSeconds(),
						detail.runLabel(), detail.isPlacing(), detail.placementGames()));
			}
		}

		sort(rows);
		return rows;
	}

	/**
	 * Applies the configured row order in place.
	 *
	 * <p>Default is the order they were collected in, so nothing to do. The
	 * others sort on data the list may not report -- rows missing it keep their
	 * relative order and sink to the bottom, rather than shuffling at random.
	 */
	private void sort(List<Row> rows) {
		SpogTiersConfig config = SpogTiersClient.config();
		SpogTiersConfig.SortOrder order =
				config == null ? SpogTiersConfig.SortOrder.DEFAULT : config.sortOrder;

		if (order != null && order != SpogTiersConfig.SortOrder.DEFAULT) {
			switch (order) {
				// Most recently earned first.
				case DATE_OBTAINED -> rows.sort(Comparator
						.comparingLong((Row row) -> row.attained() > 0 ? 0 : 1)
						.thenComparing(Comparator.comparingLong(Row::attained).reversed()));
				// Best first. ladderOrdinal covers both the numbered lists and
				// CatPVP's named ranks, which do not fit tier/position cleanly.
				case RANKING -> rows.sort(
						Comparator.comparingInt(row -> row.tier().ladderOrdinal()));
				// The same, but a row is judged on its peak where it has one, so
				// a decayed rank still sorts by how high the player once reached.
				case RANKING_PEAK -> rows.sort(
						Comparator.comparingInt(ProfileScreen::peakOrdinal));
				default -> {
				}
			}
		}

		// Placements sink below the ranks the player actually holds, whatever
		// the chosen order, with the most complete first so the run closest to
		// finishing sits nearest the ranked rows. A stable sort keeps the order
		// above from being disturbed.
		rows.sort(Comparator
				.comparingInt((Row row) -> row.placing() ? 1 : 0)
				.thenComparingInt(row -> row.placing() ? -row.placementGames() : 0));
	}

	/**
	 * The ordinal a row sorts on when peaks count: the better of the current
	 * tier and the peak, so a row never sorts worse than the rank it holds.
	 */
	private static int peakOrdinal(Row row) {
		int current = row.tier().ladderOrdinal();
		if (row.peak() == null || !row.peak().isRanked()) {
			return current;
		}
		return Math.min(current, row.peak().ladderOrdinal());
	}

	private record Row(String label, Tier tier, int accent, String iconKey, Tier peak,
			long attained, String run, boolean placing, int placementGames) {
		/** Only worth showing a peak that is actually better than the current tier. */
		boolean showsPeak() {
			if (peak == null || !peak.isRanked()) {
				return false;
			}
			if (peak.tier() != tier.tier()) {
				return peak.tier() < tier.tier();
			}
			return peak.position().ordinal() < tier.position().ordinal();
		}
	}

	private record Hover(TierList list, Row row) {
	}

	/**
	 * Tooltip for a hovered ranking, drawn in the panel's own style.
	 *
	 * <p>Ranked lists date the placement; ELO lists show the rating and how far
	 * it sits through the current tier, with a bar toward the next one.
	 */
	private void drawTierTooltip(GuiGraphicsExtractor graphics, Hover target, int mouseX, int mouseY) {
		Font font = this.font;
		PlayerTiers tiers = SpogTiersClient.cache().get(this.target, target.list());
		if (tiers == null) {
			return;
		}

		TierDetail detail = tiers.detail(target.row().label());
		List<Line> lines = new ArrayList<>();

		Tier tier = target.row().tier();
		// Named ranks are abbreviated on the card (N1), so the tooltip is where
		// the full name is spelled out.
		if (detail.isPlacing()) {
			lines.add(new Line(target.row().label() + " placement", PLACEMENT_COLOR));
			lines.add(new Line(detail.runLabel() + " games played", 0xFFE4EAF2));
		} else {
			lines.add(new Line(target.row().label() + " " + tier.fullName(), tier.color()));
		}
		if (detail.isTesting()) {
			String next = detail.hasNextTier() ? detail.nextTier() : "next tier";
			lines.add(new Line("Attempting " + next + " (" + detail.runLabel() + ")",
					PLACEMENT_COLOR));
		}
		if (tier.retired()) {
			lines.add(new Line("(Retired)", MUTED_COLOR));
		}

		boolean bar = false;
		if (detail.hasRating()) {
			// Only PVPHQ publishes a global position, and it costs a lookup, so
			// it is requested lazily the first time a row is hovered.
			Gamemode hovered = Gamemode.byKey(target.row().iconKey());
			if (hovered != null) {
				SpogTiersClient.service().requestWorldRank(
						this.target, target.list(), hovered, tier, detail.rating());
				int rank = SpogTiersClient.service()
						.worldRank(this.target, target.list(), hovered);
				if (rank > 0) {
					lines.add(new Line("Rank #" + rank, 0xFF9DB2C8));
				}
			}

			// The played record, wins against losses, with the rate spelled out
			// so a lopsided record is not read off two bare numbers.
			if (detail.hasRecord()) {
				lines.add(recordLine(detail));
			}

			// TR is progress toward the next tier, always out of 100. The old
			// line divided the rating band instead, which is a different
			// number entirely. A player still placing has neither: the run is
			// the whole story, and their rating is provisional.
			if (detail.isPlacing()) {
				bar = false;
			} else if (detail.hasTierPoints()) {
				lines.add(new Line("TR " + detail.tierPoints() + "/"
						+ TierDetail.TIER_POINT_TARGET, 0xFFE4EAF2));
				// The bar now tracks TR, so it agrees with the line above it.
				bar = true;
			} else {
				lines.add(new Line("Elo " + detail.rating(), 0xFFE4EAF2));
				// The bar shows how far through the current tier the rating
				// sits; naming the next tier is redundant.
				bar = detail.tierCeiling() > detail.tierFloor();
			}
		} else if (detail.hasAttained()) {
			lines.add(new Line("Attained " + formatDate(detail.attainedSeconds()), 0xFFE4EAF2));
		} else if (target.list().isMcPvp()) {
			// MCPvP publishes no rating, no dates and no per-kit standing: the
			// same rank and points come back whichever kit is asked for. What
			// it does have is the player's overall placing, which is worth
			// showing rather than leaving the row blank.
			if (tiers.overall() > 0) {
				lines.add(new Line("Rank #" + tiers.overall(), 0xFF9DB2C8));
			}
			if (tiers.points() > 0.0f) {
				lines.add(new Line(formatPoints(tiers.points()) + " points", 0xFFE4EAF2));
			}
			if (tiers.overall() <= 0 && tiers.points() <= 0.0f) {
				lines.add(new Line("No detail available", MUTED_COLOR));
			}
		} else {
			lines.add(new Line("No detail available", MUTED_COLOR));
		}

		// Spell the peak out rather than leaving the struck-through label to
		// speak for itself.
		if (target.row().showsPeak()) {
			Tier peak = target.row().peak();
			String label = "Peak tier " + peak.fullName();
			// Peak TR, which is what the site reports beside a peak tier. It is
			// a lifetime total rather than progress within a tier, so unlike
			// current TR it is not out of 100 and carries no denominator.
			if (detail.hasTr()) {
				label += " (" + detail.peakPoints() + " TR)";
			} else if (detail.peakRating() > 0 && detail.peakRating() != detail.rating()) {
				label += " (" + detail.peakRating() + ")";
			}
			lines.add(new Line(label, peak.color()));
		}

		// Who gave the placement, last: it is provenance rather than part of
		// the ranking, and it reads as a footnote under the numbers it
		// explains. Only MCTiers publishes this, and only while the test is
		// recent enough to still be in its history, so most rows have none.
		if (detail.hasTester()) {
			lines.add(new Line("Tested by " + detail.tester(), MUTED_COLOR));
		}

		int textWidth = 0;
		for (Line line : lines) {
			textWidth = Math.max(textWidth, font.width(line.text()));
		}
		int boxWidth = textWidth + TOOLTIP_PADDING * 2;
		int boxHeight = TOOLTIP_PADDING * 2 + lines.size() * (font.lineHeight + 2) - 2
				+ (bar ? 10 : 0);

		// Sized against the card it belongs to, so the two read as one object.
		float scale = cardScale;
		int drawnWidth = Math.round(boxWidth * scale);
		int drawnHeight = Math.round(boxHeight * scale);

		// Keep the tooltip on screen rather than letting it run off an edge.
		// Placed in screen space, then the box is drawn scaled from there.
		int boxX = Tooltips.x(mouseX, drawnWidth, width);
		int boxY = Math.clamp(mouseY - 8, 4, height - drawnHeight - 4);

		graphics.pose().pushMatrix();
		graphics.pose().translate(boxX, boxY);
		graphics.pose().scale(scale, scale);
		boxX = 0;
		boxY = 0;

		drawCardFrame(graphics, boxX, boxY, boxX + boxWidth, boxY + boxHeight);
		graphics.fill(boxX + 1, boxY + 1, boxX + boxWidth - 1, boxY + boxHeight - 1, 0xE00E1219);

		int lineY = boxY + TOOLTIP_PADDING;
		for (Line line : lines) {
			if (line.component() != null) {
				graphics.text(font, line.component(),
						boxX + TOOLTIP_PADDING, lineY, 0xFFFFFFFF);
			} else {
				graphics.text(font, Component.literal(line.text()),
						boxX + TOOLTIP_PADDING, lineY, line.color());
			}
			lineY += font.lineHeight + 2;
		}

		if (bar) {
			int barLeft = boxX + TOOLTIP_PADDING;
			int barRight = boxX + boxWidth - TOOLTIP_PADDING;
			int barTop = lineY;

			// Track and outline first, so the full width reads as the total
			// rather than the fill floating on the background.
			int barBottom = barTop + 6;
			graphics.fill(barLeft, barTop, barRight, barBottom, 0xFF10161E);
			graphics.fill(barLeft, barTop, barRight, barTop + 1, CARD_BORDER);
			graphics.fill(barLeft, barBottom - 1, barRight, barBottom, CARD_BORDER);
			graphics.fill(barLeft, barTop, barLeft + 1, barBottom, CARD_BORDER);
			graphics.fill(barRight - 1, barTop, barRight, barBottom, CARD_BORDER);

			int track = barRight - barLeft - 2;
			int filled = Math.round(track * detail.progress());
			if (filled > 0) {
				graphics.fill(barLeft + 1, barTop + 1, barLeft + 1 + filled, barBottom - 1,
						target.row().tier().color());
			}
		}

		graphics.pose().popMatrix();
	}

	/** Half-strength version of a colour, for the struck-through peak. */
	private static int fade(int argb) {
		return (0x80 << 24) | (argb & 0xFFFFFF);
	}

	/** Names the region in full, in the tag's own colour. */
	/**
	 * Puts the button row where the panel's current width wants it.
	 *
	 * <p>The buttons are real widgets, so they cannot be drawn inside the
	 * panel's transform; they are moved to match it instead. Done every frame
	 * because the panel's width follows the window, which {@code init} cannot
	 * know about ahead of time.
	 */
	private void layoutButtons() {
		if (closeButton == null) {
			return;
		}

		float scale = panelScale();
		int inset = Math.round(CARD_PADDING * scale);
		int rowWidth = profileWidth() - inset * 2;
		// The whole row shrinks together: icons that kept their full size ate
		// the Close button's width once the panel narrowed.
		int buttonHeight = Math.max(12, Math.round(20 * scale));
		int iconSize = buttonHeight;
		int left = MARGIN + inset;

		// Anchored to the panel's own bottom edge rather than the window's:
		// the two part company once the panel stops running full height.
		// Computed rather than read from panelBottom, which drawHeader only
		// sets later in the frame -- reading it here would trail by a frame and
		// start at zero.
		int top = MARGIN + profilePanelHeight() - inset - buttonHeight;

		int gap = Math.max(2, Math.round(4 * scale));

		closeButton.setX(left);
		closeButton.setY(top);
		closeButton.setWidth(Math.max(20, rowWidth - iconSize * 2 - gap * 2));
		closeButton.setHeight(buttonHeight);

		copyButton.setX(left + rowWidth - iconSize * 2 - gap);
		copyButton.setY(top);
		copyButton.setSize(iconSize, buttonHeight);

		refreshButton.setX(left + rowWidth - iconSize);
		refreshButton.setY(top);
		refreshButton.setSize(iconSize, buttonHeight);
	}

	/** The panel's width, capped to its share of the window. */
	private int profileWidth() {
		return Math.min(PROFILE_WIDTH, Math.round(width * PROFILE_WIDTH_SHARE));
	}

	/**
	 * How much the panel's contents are shrunk by.
	 *
	 * <p>The panel narrows once it would otherwise take too much of the window,
	 * and everything inside it -- the face, the name, the history, the buttons
	 * -- is drawn at this scale so it keeps its proportions instead of
	 * overflowing a box that is no longer wide enough for it.
	 */
	private float panelScale() {
		return profileWidth() / (float) PROFILE_WIDTH;
	}

	/** The model's width, which follows the panel so it stays inside it. */
	private int skinWidgetWidth() {
		return Math.min(SKIN_WIDTH, profileWidth() - CARD_PADDING * 2);
	}

	/** A one-line tooltip in the panel's own style. */
	private void drawLabelTooltip(GuiGraphicsExtractor graphics, String text,
			int mouseX, int mouseY) {
		Font font = this.font;
		int boxWidth = font.width(text) + TOOLTIP_PADDING * 2;
		int boxHeight = font.lineHeight + TOOLTIP_PADDING * 2;

		int boxX = Tooltips.x(mouseX, boxWidth, width);
		int boxY = Math.clamp(mouseY - 8, 4, height - boxHeight - 4);

		drawCardFrame(graphics, boxX, boxY, boxX + boxWidth, boxY + boxHeight);
		graphics.fill(boxX + 1, boxY + 1, boxX + boxWidth - 1, boxY + boxHeight - 1, 0xE00E1219);
		graphics.text(font, Component.literal(text),
				boxX + TOOLTIP_PADDING, boxY + TOOLTIP_PADDING, 0xFFE4EAF2);
	}

	/** How long that list took to answer, and nothing else. */
	private void drawResponseTooltip(GuiGraphicsExtractor graphics, TierList list,
			int mouseX, int mouseY) {
		int millis = SpogTiersClient.service().responseMillis(list);
		if (millis < 0) {
			return;
		}

		Font font = this.font;
		String text = millis + "ms";
		int boxWidth = font.width(text) + TOOLTIP_PADDING * 2;
		int boxHeight = font.lineHeight + TOOLTIP_PADDING * 2;

		// This one hangs off a card header, so it matches the cards as well.
		float scale = cardScale;
		int boxX = Tooltips.x(mouseX, Math.round(boxWidth * scale), width);
		int boxY = Math.clamp(mouseY - 8, 4, height - Math.round(boxHeight * scale) - 4);

		graphics.pose().pushMatrix();
		graphics.pose().translate(boxX, boxY);
		graphics.pose().scale(scale, scale);
		drawCardFrame(graphics, 0, 0, boxWidth, boxHeight);
		graphics.fill(1, 1, boxWidth - 1, boxHeight - 1, 0xE00E1219);
		graphics.text(font, Component.literal(text),
				TOOLTIP_PADDING, TOOLTIP_PADDING, 0xFFE4EAF2);
		graphics.pose().popMatrix();
	}

	private void drawRegionTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		Font font = this.font;
		String name = regionName(tagRegion);
		int color = regionForeground(tagRegion);

		int boxWidth = font.width(name) + TOOLTIP_PADDING * 2;
		int boxHeight = font.lineHeight + TOOLTIP_PADDING * 2;
		int boxX = Tooltips.x(mouseX, boxWidth, width);
		int boxY = Math.clamp(mouseY - 8, 4, height - boxHeight - 4);

		drawCardFrame(graphics, boxX, boxY, boxX + boxWidth, boxY + boxHeight);
		graphics.fill(boxX + 1, boxY + 1, boxX + boxWidth - 1, boxY + boxHeight - 1, 0xE00E1219);
		graphics.text(font, Component.literal(name),
				boxX + TOOLTIP_PADDING, boxY + TOOLTIP_PADDING, color);
	}

	/** Names the list and the grade, in the grade's own colour. */
	private void drawGradeTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		Font font = this.font;
		String text = "DoorSMP: " + gradeLabel;

		int boxWidth = font.width(text) + TOOLTIP_PADDING * 2;
		int boxHeight = font.lineHeight + TOOLTIP_PADDING * 2;
		int boxX = Tooltips.x(mouseX, boxWidth, width);
		int boxY = Math.clamp(mouseY - 8, 4, height - boxHeight - 4);

		drawCardFrame(graphics, boxX, boxY, boxX + boxWidth, boxY + boxHeight);
		graphics.fill(boxX + 1, boxY + 1, boxX + boxWidth - 1, boxY + boxHeight - 1, 0xE00E1219);
		graphics.text(font, Component.literal(text),
				boxX + TOOLTIP_PADDING, boxY + TOOLTIP_PADDING, gradeColor);
	}

	private static String regionName(String region) {
		return switch (region) {
			case "NA" -> "North America";
			case "EU" -> "Europe";
			case "AS" -> "Asia";
			case "AU", "OCE", "OC" -> "Oceania";
			case "SA" -> "South America";
			case "AF" -> "Africa";
			case "ME" -> "Middle East";
			default -> region;
		};
	}

	/**
	 * One tooltip line.
	 *
	 * <p>Most lines are a string in a single colour. A line that needs more
	 * than one -- a win/loss record, where the two halves are coloured against
	 * each other -- carries a pre-styled component instead, and {@code text} is
	 * kept alongside so the box can still be measured.
	 */
	private record Line(String text, int color, Component component) {
		Line(String text, int color) {
			this(text, color, null);
		}
	}

	/**
	 * A win/loss record: wins in green, losses in red, the rate after in grey.
	 */
	private Line recordLine(TierDetail detail) {
		String wins = String.valueOf(detail.wins());
		String losses = String.valueOf(detail.losses());
		String rate = " (" + detail.winPercent() + "%)";

		Component component = Component.literal(wins)
				.setStyle(Style.EMPTY.withColor(0xFF7FD186))
				.append(Component.literal("W ")
						.setStyle(Style.EMPTY.withColor(0xFF7FD186)))
				.append(Component.literal(losses)
						.setStyle(Style.EMPTY.withColor(0xFFD97F7F)))
				.append(Component.literal("L")
						.setStyle(Style.EMPTY.withColor(0xFFD97F7F)))
				.append(Component.literal(rate)
						.setStyle(Style.EMPTY.withColor(MUTED_COLOR)));

		return new Line(wins + "W " + losses + "L" + rate, 0xFFE4EAF2, component);
	}

	/** Points without a trailing {@code .0}, since half points are common. */
	private static String formatPoints(float points) {
		return points == Math.rint(points)
				? String.valueOf((int) points)
				: String.valueOf(points);
	}

	/** Formats an epoch-seconds timestamp as a plain calendar date. */
	private static String formatDate(long epochSeconds) {
		return DATE_FORMAT.format(Instant.ofEpochSecond(epochSeconds));
	}

	private record Card(TierList list, List<Row> rows) {
	}

	/**
	 * Closes on a second press of the Quick Tiers key.
	 *
	 * <p>Vanilla only feeds key presses to KeyMapping while no screen is open,
	 * so the binding's own tick handler can never see this one -- the screen
	 * has to match the key itself.
	 */
	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		if (QuickTiers.binding().matches(event)) {
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
