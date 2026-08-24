package com.spog.tiers.client.gui;

import com.mojang.authlib.GameProfile;
import com.spog.tiers.SpogTiers;
import com.spog.tiers.SpogTiersClient;
import com.spog.tiers.client.QuickTiers;
import com.spog.tiers.config.SpogTiersConfig;
import com.spog.tiers.data.Gamemode;
import com.spog.tiers.data.NameHistory;
import com.spog.tiers.data.PlayerTiers;
import com.spog.tiers.data.Tier;
import com.spog.tiers.data.TierDetail;
import com.spog.tiers.data.TierList;
import com.spog.tiers.data.TierService;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.entity.player.SkinTextures;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
	private static final int PROFILE_WIDTH = 172;
	private static final int SKIN_WIDTH = 110;
	private static final int SKIN_HEIGHT = 170;
	/** Rows of past names shown under the model before scrolling is needed. */
	private static final int HISTORY_ROWS = 5;
	private static final int HISTORY_ROW_HEIGHT = 11;
	private static final int CARD_PADDING = 10;
	private static final int CARD_GAP = 10;
	/** Cards are a fixed size so two lists look the same as four. */
	private static final int CARD_WIDTH = 162;
	private static final int CARD_ROWS = 11;
	private static final int LOGO_SIZE = 14;
	private static final int MODE_ICON = 12;
	private static final int TOOLTIP_PADDING = 6;
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

	private Supplier<SkinTextures> skin;
	/** Row under the cursor this frame, resolved during card layout. */
	private Hover hover;
	/** List whose header is under the cursor, for the response-time tooltip. */
	private TierList headerHover;
	/** Region tag bounds, so hovering it can name the region in full. */
	private int tagLeft;
	private int tagTop;
	private int tagRight;
	private int tagBottom;
	private String tagRegion = "";
	private PanelButton closeButton;
	private IconButton refreshButton;
	/** Vertical band the name history occupies, set during layout. */
	private int historyTop;
	private int historyBottom;
	private int historyScroll;
	/** Rows that did not fit, so the wheel knows how far it may travel. */
	private int historyMaxScroll;

	public ProfileScreen(GameProfile profile) {
		super(Text.literal(profile.name()));
		this.profile = profile;
		this.target = profile.id();
		this.playerName = profile.name();
	}

	@Override
	protected void init() {
		MinecraftClient client = MinecraftClient.getInstance();

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
		skin = client.getSkinProvider().supplySkinTextures(profile, false);

		int cardLeft = MARGIN;
		int cardTop = MARGIN;
		int cardBottom = height - MARGIN;

		// The name history sits between the model and the close button, so it is
		// carved out first and the model gets whatever remains. On a very short
		// window the model has a minimum height and would grow back into this
		// band, so the history yields rather than being drawn over.
		int historyHeight = textRenderer.fontHeight + 4 + HISTORY_ROWS * HISTORY_ROW_HEIGHT;
		historyBottom = cardBottom - CARD_PADDING - 20 - 10;
		historyTop = Math.max(
				cardTop + CARD_PADDING + FACE_SIZE + 12 + 80 + 8,
				historyBottom - historyHeight);

		// Fit the model to whatever is left between the header and the history,
		// so it never spills out of the profile card.
		int skinTop = cardTop + CARD_PADDING + FACE_SIZE + 12;
		int skinBottom = historyTop - 8;
		int skinHeight = Math.clamp(skinBottom - skinTop, 80, SKIN_HEIGHT);

		AnimatedSkinWidget skinWidget = new AnimatedSkinWidget(
				SKIN_WIDTH, skinHeight, client.getLoadedEntityModels(), skin);
		skinWidget.setPosition(
				cardLeft + (PROFILE_WIDTH - SKIN_WIDTH) / 2,
				skinTop + Math.max(0, (skinBottom - skinTop - skinHeight) / 2));
		addDrawableChild(skinWidget);

		// Close takes the row, less a square on the right for refresh.
		int buttonTop = cardBottom - CARD_PADDING - 20;
		int rowWidth = PROFILE_WIDTH - CARD_PADDING * 2;
		int refreshSize = 20;

		closeButton = new PanelButton(
				cardLeft + CARD_PADDING,
				buttonTop,
				rowWidth - refreshSize - 4,
				20,
				Text.literal("Close"),
				button -> close());
		addDrawableChild(closeButton);

		refreshButton = new IconButton(
				cardLeft + CARD_PADDING + rowWidth - refreshSize,
				buttonTop,
				refreshSize,
				20,
				Text.literal("Refresh"),
				button -> refresh());
		addDrawableChild(refreshButton);
	}

	@Override
	public void render(DrawContext graphics, int mouseX, int mouseY, float partialTick) {
		// NB: the blurred background is drawn for us by the framework, which
		// calls extractBackground immediately before this method. Blurring again
		// here throws "Can only blur once per frame".
		graphics.fill(0, 0, width, height, 0xC00B0E13);

		hover = null;
		headerHover = null;
		drawHeader(graphics);
		drawNameHistory(graphics);
		drawCards(graphics, mouseX, mouseY);

		// Widgets (the skin model included) render after our fills, so they are
		// not painted over.
		super.render(graphics, mouseX, mouseY, partialTick);

		// Tooltip last and outside the card transform, so it is never clipped
		// or scaled with the grid.
		if (hover != null) {
			drawTierTooltip(graphics, hover, mouseX, mouseY);
		} else if (headerHover != null) {
			drawResponseTooltip(graphics, headerHover, mouseX, mouseY);
		} else if (!tagRegion.isEmpty()
				&& mouseX >= tagLeft && mouseX <= tagRight
				&& mouseY >= tagTop && mouseY <= tagBottom) {
			drawRegionTooltip(graphics, mouseX, mouseY);
		}

	}

	/** The profile card: face, name, region tag, skin model and buttons. */
	private void drawHeader(DrawContext graphics) {
		TextRenderer textRenderer = this.textRenderer;

		int left = MARGIN;
		int top = MARGIN;
		drawCardFrame(graphics, left, top, left + PROFILE_WIDTH, height - MARGIN);

		int innerX = left + CARD_PADDING;
		int y = top + CARD_PADDING;

		drawFace(graphics, innerX, y);

		int nameX = innerX + FACE_SIZE + 6;
		// +1 so the text sits optically centred against the face icon.
		int nameY = y + (FACE_SIZE - textRenderer.fontHeight) / 2 + 1;
		graphics.drawTextWithShadow(textRenderer, Text.literal(playerName), nameX, nameY, 0xFFFFFFFF);

		// Region reads as a small boxed tag beside the name.
		String region = region();
		tagRegion = region;
		if (!region.isEmpty()) {
			drawTag(graphics, nameX + textRenderer.getWidth(playerName) + 5, nameY - 3, region);
		}

		// Overall standing, when the player is near the top of a list that
		// publishes one.

	}

	/**
	 * Past names under the model, newest first, each with how long ago it was
	 * taken.
	 *
	 * <p>Only names before the current one are listed -- the current one is
	 * already at the top of the card. Accounts rename a lot (some have dozens),
	 * so the list is clipped to its band and scrolls.
	 */
	private void drawNameHistory(DrawContext graphics) {
		TextRenderer textRenderer = this.textRenderer;
		int x = MARGIN + CARD_PADDING;
		int right = MARGIN + PROFILE_WIDTH - CARD_PADDING;
		int y = historyTop;

		graphics.drawTextWithShadow(textRenderer, Text.literal("Name history"), x, y, LABEL_COLOR);
		y += textRenderer.fontHeight + 4;

		NameHistory history = SpogTiersClient.service().nameHistory(target);
		if (history == null) {
			graphics.drawTextWithShadow(textRenderer, Text.literal("Loading..."), x, y, MUTED_COLOR);
			historyMaxScroll = 0;
			return;
		}

		List<NameHistory.Entry> previous = history.previous();
		if (previous.isEmpty()) {
			graphics.drawTextWithShadow(textRenderer, Text.literal("No previous names"), x, y, MUTED_COLOR);
			historyMaxScroll = 0;
			return;
		}

		int visible = Math.max(1, (historyBottom - y) / HISTORY_ROW_HEIGHT);
		historyMaxScroll = Math.max(0, previous.size() - visible);
		historyScroll = Math.clamp(historyScroll, 0, historyMaxScroll);

		// Clipped so a long history cannot spill over the close button.
		graphics.enableScissor(x, y, right, historyBottom);
		for (int i = 0; i < visible && i + historyScroll < previous.size(); i++) {
			NameHistory.Entry entry = previous.get(i + historyScroll);
			int rowY = y + i * HISTORY_ROW_HEIGHT;

			String ago = entry.isDated() ? timeAgo(entry.changedAt()) : "";
			int agoWidth = ago.isEmpty() ? 0 : textRenderer.getWidth(ago);

			graphics.drawTextWithShadow(textRenderer, Text.literal(
							trim(entry.name(), right - x - agoWidth - 6)),
					x, rowY, 0xFFD5DCE5);
			if (!ago.isEmpty()) {
				graphics.drawTextWithShadow(textRenderer, Text.literal(ago), right - agoWidth, rowY, MUTED_COLOR);
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
	}

	/**
	 * Scrolls the name history when the cursor is over it.
	 *
	 * <p>Scoped to the band, and only when there is something to scroll, so the
	 * wheel keeps its usual meaning everywhere else on the screen.
	 */
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
		boolean overHistory = mouseX >= MARGIN && mouseX <= MARGIN + PROFILE_WIDTH
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
		while (textRenderer.getWidth(out) > max && out.length() > 1) {
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
	private int drawRankTag(DrawContext graphics, int x, int y, int rank) {
		if (rank < 1 || rank > TierService.TOP_RANK_LIMIT) {
			return 0;
		}
		TextRenderer textRenderer = this.textRenderer;
		String text = "#" + rank;
		int boxWidth = textRenderer.getWidth(text) + 8;
		int boxHeight = textRenderer.fontHeight + 5;

		int foreground = rankForeground(rank);
		int background = rankBackground(rank);
		int border = (0xB0 << 24) | (foreground & 0xFFFFFF);

		graphics.fill(x, y, x + boxWidth, y + boxHeight, background);
		graphics.fill(x, y, x + boxWidth, y + 1, border);
		graphics.fill(x, y + boxHeight - 1, x + boxWidth, y + boxHeight, border);
		graphics.fill(x, y, x + 1, y + boxHeight, border);
		graphics.fill(x + boxWidth - 1, y, x + boxWidth, y + boxHeight, border);

		graphics.drawTextWithShadow(textRenderer, Text.literal(text), x + 4, y + 3, foreground);
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
	private void drawTag(DrawContext graphics, int x, int y, String text) {
		TextRenderer textRenderer = this.textRenderer;
		int boxWidth = textRenderer.getWidth(text) + 8;
		int boxHeight = textRenderer.fontHeight + 5;

		tagLeft = x;
		tagTop = y;
		tagRight = x + boxWidth;
		tagBottom = y + boxHeight;

		int foreground = regionForeground(text);
		int background = regionBackground(text);
		int border = (0xB0 << 24) | (foreground & 0xFFFFFF);

		graphics.fill(x, y, x + boxWidth, y + boxHeight, background);
		graphics.fill(x, y, x + boxWidth, y + 1, border);
		graphics.fill(x, y + boxHeight - 1, x + boxWidth, y + boxHeight, border);
		graphics.fill(x, y, x + 1, y + boxHeight, border);
		graphics.fill(x + boxWidth - 1, y, x + boxWidth, y + boxHeight, border);

		graphics.drawTextWithShadow(textRenderer, Text.literal(text), x + 4, y + 3, foreground);
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

	private void drawCardFrame(DrawContext graphics, int left, int top, int right, int bottom) {
		graphics.fill(left, top, right, bottom, CARD_FILL);
		graphics.fill(left, top, right, top + 1, CARD_BORDER);
		graphics.fill(left, bottom - 1, right, bottom, CARD_BORDER);
		graphics.fill(left, top, left + 1, bottom, CARD_BORDER);
		graphics.fill(right - 1, top, right, bottom, CARD_BORDER);
	}

	/** Draws the head, then the hat layer, scaled up from the 64x64 skin sheet. */
	private void drawFace(DrawContext graphics, int x, int y) {
		SkinTextures resolved = skin == null ? null : skin.get();
		if (resolved == null) {
			return;
		}
		graphics.drawTexture(RenderPipelines.GUI_TEXTURED, resolved.body().texturePath(),
				x, y, 8.0f, 8.0f, FACE_SIZE, FACE_SIZE, 8, 8, 64, 64);
		graphics.drawTexture(RenderPipelines.GUI_TEXTURED, resolved.body().texturePath(),
				x, y, 40.0f, 8.0f, FACE_SIZE, FACE_SIZE, 8, 8, 64, 64);
	}

	/**
	 * The player's region code.
	 *
	 * <p>PvPTiers, SubTiers and MCTiers report proper codes ("EU", "NA"), so
	 * those win. PVPHQ instead lists the <em>server locations</em> a player has
	 * queued on ("MONTREAL", "LOS_ANGELES"), which we fold down to a continent
	 * so the tag always reads as a region rather than a city.
	 */
	private String region() {
		Map<TierList, PlayerTiers> all = SpogTiersClient.cache().allLists(target);

		for (TierList list : TierList.values()) {
			if (list.isPvpHq()) {
				continue;
			}
			PlayerTiers tiers = all.get(list);
			if (tiers != null && isRegionCode(tiers.region())) {
				return tiers.region().toUpperCase(Locale.ROOT);
			}
		}

		PlayerTiers pvpHq = all.get(TierList.PVPHQ);
		return pvpHq == null ? "" : continentOf(pvpHq.region());
	}

	/** True for short codes like EU/NA/AS, false for "??" and city names. */
	private static boolean isRegionCode(String raw) {
		if (raw == null || raw.length() < 2 || raw.length() > 4) {
			return false;
		}
		for (int i = 0; i < raw.length(); i++) {
			if (!Character.isLetter(raw.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	/** Folds a PVPHQ server location down to a continent code. */
	private static String continentOf(String location) {
		if (location == null || location.isEmpty()) {
			return "";
		}
		return switch (location.toUpperCase(Locale.ROOT)) {
			case "MONTREAL", "TORONTO", "LOS_ANGELES", "PORTLAND", "CHICAGO",
					"ASHBURN", "MIAMI", "DALLAS", "NEW_YORK", "SEATTLE",
					"DENVER", "ATLANTA", "PHOENIX", "VANCOUVER" -> "NA";
			case "LONDON", "FRANKFURT", "AMSTERDAM", "PARIS", "WARSAW",
					"MADRID", "MILAN", "STOCKHOLM", "HELSINKI", "DUBLIN" -> "EU";
			case "SINGAPORE", "TOKYO", "SEOUL", "MUMBAI", "HONG_KONG",
					"OSAKA", "JAKARTA" -> "AS";
			case "SYDNEY", "MELBOURNE", "AUCKLAND" -> "OCE";
			case "SAO_PAULO", "SANTIAGO", "BUENOS_AIRES", "LIMA", "BOGOTA" -> "SA";
			case "JOHANNESBURG", "CAPE_TOWN", "LAGOS" -> "AF";
			default -> "";
		};
	}

	/** One fixed-size card per ranked list, arranged in a balanced grid. */
	private void drawCards(DrawContext graphics, int mouseX, int mouseY) {
		TextRenderer textRenderer = this.textRenderer;
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

		int contentLeft = MARGIN + PROFILE_WIDTH + MARGIN;
		int contentWidth = Math.max(160, width - contentLeft - MARGIN);
		int contentTop = MARGIN;
		int contentBottom = height - MARGIN;

		if (cards.isEmpty()) {
			String message = SpogTiersClient.cache().isPending(target)
					? "Loading rankings..."
					: playerName + " is unranked";
			graphics.drawTextWithShadow(textRenderer, Text.literal(message),
					contentLeft + (contentWidth - textRenderer.getWidth(message)) / 2,
					contentTop + 40,
					MUTED_COLOR);
			return;
		}

		// Fixed geometry: cards are the same size whether two lists load or
		// four. The grid is sized to fill the profile card's height so the
		// longest list (CatPVP ranks 14 modes) has room instead of
		// spilling past the card edge.
		int rowsNeeded = CARD_ROWS;
		for (Card card : cards) {
			rowsNeeded = Math.max(rowsNeeded, card.rows().size());
		}

		// Always size cards as if the grid were two rows deep, so a single row
		// of cards is the same height as one row of a 2x2 grid rather than
		// stretching to fill the screen.
		int available = (contentBottom - contentTop) - CARD_GAP;
		int cardHeight = Math.max(
				CARD_PADDING * 2 + 18 + rowsNeeded * ROW_HEIGHT,
				available / 2);

		// Prefer a balanced grid over a full first row: 4 cards read better as
		// 2x2 than 3+1, and 3 stay on one row.
		int perRow = switch (cards.size()) {
			case 1 -> 1;
			case 2 -> 2;
			case 3 -> 3;
			default -> (cards.size() + 1) / 2;
		};
		int rowCount = (cards.size() + perRow - 1) / perRow;

		int blockWidth = perRow * CARD_WIDTH + (perRow - 1) * CARD_GAP;
		int blockHeight = rowCount * cardHeight + (rowCount - 1) * CARD_GAP;

		// Scale only to fit; never blow the cards up when there is spare room.
		int availableHeight = contentBottom - contentTop;
		float scale = Math.min(1.0f, Math.min(
				(float) contentWidth / blockWidth,
				(float) availableHeight / blockHeight));

		int originX = contentLeft + (contentWidth - Math.round(blockWidth * scale)) / 2;
		int originY = contentTop + Math.max(0, (availableHeight - Math.round(blockHeight * scale)) / 2);

		graphics.getMatrices().pushMatrix();
		graphics.getMatrices().translate(originX, originY);
		graphics.getMatrices().scale(scale, scale);

		for (int index = 0; index < cards.size(); index++) {
			int row = index / perRow;
			int column = index % perRow;

			int inThisRow = Math.min(perRow, cards.size() - row * perRow);
			int rowWidth = inThisRow * CARD_WIDTH + (inThisRow - 1) * CARD_GAP;
			int rowLeft = (blockWidth - rowWidth) / 2;

			int x = rowLeft + column * (CARD_WIDTH + CARD_GAP);
			int y = row * (cardHeight + CARD_GAP);

			// Mouse mapped into the scaled card space, so hit-testing matches
			// what is actually drawn.
			float localX = (mouseX - originX) / scale;
			float localY = (mouseY - originY) / scale;
			drawCard(graphics, cards.get(index), x, y, cardHeight, localX, localY);
		}

		graphics.getMatrices().popMatrix();
	}

	private void drawCard(DrawContext graphics, Card card, int x, int y, int cardHeight,
			float localX, float localY) {
		TextRenderer textRenderer = this.textRenderer;

		drawCardFrame(graphics, x, y, x + CARD_WIDTH, y + cardHeight);

		int textY = y + CARD_PADDING;

		// The player's standing on this list, which belongs beside the list it
		// came from rather than beside their name.
		SpogTiersClient.service().requestTopRanks(card.list(), null);
		int listRank = SpogTiersClient.service().topRank(target, card.list(), null);
		boolean showRank = listRank > 0 && listRank <= TierService.TOP_RANK_LIMIT;

		// Logo, title and badge are centred together as one unit.
		String title = card.list().displayName();
		int badgeWidth = showRank ? textRenderer.getWidth("#" + listRank) + 8 + 4 : 0;
		int headerWidth = LOGO_SIZE + 4 + textRenderer.getWidth(title) + badgeWidth;
		int headerX = x + (CARD_WIDTH - headerWidth) / 2;

		Identifier logo = Identifier.of(
				SpogTiers.MOD_ID, card.list().logoPath());
		graphics.drawTexture(RenderPipelines.GUI_TEXTURED, logo,
				headerX, textY + (textRenderer.fontHeight - LOGO_SIZE) / 2 - 2,
				0.0f, 0.0f, LOGO_SIZE, LOGO_SIZE, 64, 64, 64, 64);
		graphics.drawTextWithShadow(textRenderer, Text.literal(title),
				headerX + LOGO_SIZE + 4, textY, 0xFFFFFFFF);
		// Logo and name together are the hover target, which is the whole
		// header block rather than either piece alone.
		if (localX >= headerX && localX <= headerX + LOGO_SIZE + 4 + textRenderer.getWidth(title)
				&& localY >= textY - 3 && localY <= textY + textRenderer.fontHeight + 2) {
			headerHover = card.list();
		}

		if (showRank) {
			drawRankTag(graphics, headerX + LOGO_SIZE + 4 + textRenderer.getWidth(title) + 4,
					textY - 3, listRank);
		}

		textY += textRenderer.fontHeight + 4;
		graphics.fill(x + CARD_PADDING, textY, x + CARD_WIDTH - CARD_PADDING, textY + 1, 0x28FFFFFF);
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
				widestValue = Math.max(widestValue, textRenderer.getWidth(row.tier().bareLabel()));
			}
			if (row.showsPeak()) {
				widestPeak = Math.max(widestPeak, textRenderer.getWidth(row.peak().label()));
			}
		}
		int valueX = x + CARD_WIDTH - CARD_PADDING - widestValue;

		for (Row row : card.rows()) {
			// Whole row is the hit target, not just the label.
			if (localX >= x && localX <= x + CARD_WIDTH
					&& localY >= textY - 2 && localY < textY + ROW_HEIGHT - 2) {
				hover = new Hover(card.list(), row);
			}

			int labelX = textX;
			// Only blit artwork the list actually ships: a provider can rank a
			// mode we have no icon for, and a missing texture draws the magenta
			// chequer across the row.
			if (row.iconKey() != null && hasIcon(card.list(), row.iconKey())) {
				Identifier icon = Identifier.of(
						SpogTiers.MOD_ID, card.list().modeIconPath(row.iconKey()));
				graphics.drawTexture(RenderPipelines.GUI_TEXTURED, icon,
						textX, textY - 2, 0.0f, 0.0f,
						MODE_ICON, MODE_ICON, 64, 64, 64, 64);
				labelX += MODE_ICON + 3;
			}
			graphics.drawTextWithShadow(textRenderer, Text.literal(row.label()), labelX, textY, row.accent());



			// Peak sits to the left of the current tier, struck through to read
			// as "used to be".
			if (row.showsPeak()) {
				String peakLabel = row.peak().label();
				int peakX = valueX - widestPeak - 5 + (widestPeak - textRenderer.getWidth(peakLabel));
				int peakColor = fade(row.peak().color());
				graphics.drawTextWithShadow(textRenderer, Text.literal(peakLabel), peakX, textY, peakColor);
				graphics.fill(peakX, textY + textRenderer.fontHeight / 2,
						peakX + textRenderer.getWidth(peakLabel), textY + textRenderer.fontHeight / 2 + 1, peakColor);
			}

			// The value column ends here, so anything drawn in it is aligned to
			// this edge rather than to the tier column's own left edge.
			int valueRight = x + CARD_WIDTH - CARD_PADDING;

			// While placing there is no tier to show, so the run goes in its
			// place -- that is the useful fact about the row.
			if (row.placing()) {
				String run = row.run();
				graphics.drawTextWithShadow(textRenderer, Text.literal(run),
						valueRight - textRenderer.getWidth(run), textY, PLACEMENT_COLOR);
				textY += ROW_HEIGHT;
				continue;
			}

			// Draw from the bare label's slot so the R extends leftward and the
			// tier codes themselves stay in one column.
			String label = row.tier().label();
			int labelOffset = textRenderer.getWidth(label) - textRenderer.getWidth(row.tier().bareLabel());
			graphics.drawTextWithShadow(textRenderer, Text.literal(label),
					valueX - labelOffset, textY, row.tier().color());

			// A test run sits in brackets to the left of the tier it is trying
			// to leave, so the tier column itself stays aligned.
			if (!row.run().isEmpty() && !row.placing()) {
				String progress = "(" + row.run() + ")";
				graphics.drawTextWithShadow(textRenderer, Text.literal(progress),
						valueX - labelOffset - 4 - textRenderer.getWidth(progress),
						textY, PLACEMENT_COLOR);
			}
			textY += ROW_HEIGHT;
		}
	}

	/** Whether placement rows are wanted at all. */
	private static boolean showPlacements() {
		SpogTiersConfig config = SpogTiersClient.config();
		return config == null || config.showPlacements;
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
	private void drawTierTooltip(DrawContext graphics, Hover target, int mouseX, int mouseY) {
		TextRenderer textRenderer = this.textRenderer;
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

		int textWidth = 0;
		for (Line line : lines) {
			textWidth = Math.max(textWidth, textRenderer.getWidth(line.text()));
		}
		int boxWidth = textWidth + TOOLTIP_PADDING * 2;
		int boxHeight = TOOLTIP_PADDING * 2 + lines.size() * (textRenderer.fontHeight + 2) - 2
				+ (bar ? 10 : 0);

		// Keep the tooltip on screen rather than letting it run off an edge.
		int boxX = Math.min(mouseX + 12, width - boxWidth - 4);
		int boxY = Math.clamp(mouseY - 8, 4, height - boxHeight - 4);

		drawCardFrame(graphics, boxX, boxY, boxX + boxWidth, boxY + boxHeight);
		graphics.fill(boxX + 1, boxY + 1, boxX + boxWidth - 1, boxY + boxHeight - 1, 0xE00E1219);

		int lineY = boxY + TOOLTIP_PADDING;
		for (Line line : lines) {
			graphics.drawTextWithShadow(textRenderer, Text.literal(line.text()),
					boxX + TOOLTIP_PADDING, lineY, line.color());
			lineY += textRenderer.fontHeight + 2;
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
	}

	/** Half-strength version of a colour, for the struck-through peak. */
	private static int fade(int argb) {
		return (0x80 << 24) | (argb & 0xFFFFFF);
	}

	/** Names the region in full, in the tag's own colour. */
	/** How long that list took to answer, and nothing else. */
	private void drawResponseTooltip(DrawContext graphics, TierList list,
			int mouseX, int mouseY) {
		int millis = SpogTiersClient.service().responseMillis(list);
		if (millis < 0) {
			return;
		}

		TextRenderer textRenderer = this.textRenderer;
		String text = millis + "ms";
		int boxWidth = textRenderer.getWidth(text) + TOOLTIP_PADDING * 2;
		int boxHeight = textRenderer.fontHeight + TOOLTIP_PADDING * 2;

		int boxX = Math.min(mouseX + 12, width - boxWidth - 4);
		int boxY = Math.clamp(mouseY - 8, 4, height - boxHeight - 4);

		drawCardFrame(graphics, boxX, boxY, boxX + boxWidth, boxY + boxHeight);
		graphics.fill(boxX + 1, boxY + 1, boxX + boxWidth - 1, boxY + boxHeight - 1, 0xE00E1219);
		graphics.drawTextWithShadow(textRenderer, Text.literal(text),
				boxX + TOOLTIP_PADDING, boxY + TOOLTIP_PADDING, 0xFFE4EAF2);
	}

	private void drawRegionTooltip(DrawContext graphics, int mouseX, int mouseY) {
		TextRenderer textRenderer = this.textRenderer;
		String name = regionName(tagRegion);
		int color = regionForeground(tagRegion);

		int boxWidth = textRenderer.getWidth(name) + TOOLTIP_PADDING * 2;
		int boxHeight = textRenderer.fontHeight + TOOLTIP_PADDING * 2;
		int boxX = Math.min(mouseX + 12, width - boxWidth - 4);
		int boxY = Math.clamp(mouseY - 8, 4, height - boxHeight - 4);

		drawCardFrame(graphics, boxX, boxY, boxX + boxWidth, boxY + boxHeight);
		graphics.fill(boxX + 1, boxY + 1, boxX + boxWidth - 1, boxY + boxHeight - 1, 0xE00E1219);
		graphics.drawTextWithShadow(textRenderer, Text.literal(name),
				boxX + TOOLTIP_PADDING, boxY + TOOLTIP_PADDING, color);
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

	private record Line(String text, int color) {
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
	 * <p>Vanilla only feeds key presses to KeyBinding while no screen is open,
	 * so the binding's own tick handler can never see this one -- the screen
	 * has to match the key itself.
	 */
	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyInput event) {
		if (QuickTiers.binding().matchesKey(event)) {
			close();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
