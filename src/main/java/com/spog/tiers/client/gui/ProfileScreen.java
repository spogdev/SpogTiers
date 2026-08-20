package com.spog.tiers.client.gui;

import com.mojang.authlib.GameProfile;
import com.spog.tiers.SpogTiers;
import com.spog.tiers.SpogTiersClient;
import com.spog.tiers.data.Gamemode;
import com.spog.tiers.data.PlayerTiers;
import com.spog.tiers.data.Tier;
import com.spog.tiers.data.TierDetail;
import com.spog.tiers.data.TierList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
	private PanelButton closeButton;

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
		SpogTiersClient.service().request(target);

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

		// Fit the model to whatever is left between the header and the button,
		// so it never spills out of the profile card.
		int skinTop = cardTop + CARD_PADDING + FACE_SIZE + 12;
		int skinBottom = cardBottom - CARD_PADDING - 20 - 10;
		int skinHeight = Math.clamp(skinBottom - skinTop, 80, SKIN_HEIGHT);

		AnimatedSkinWidget skinWidget = new AnimatedSkinWidget(
				SKIN_WIDTH, skinHeight, client.getEntityModels(), skin);
		skinWidget.setPosition(
				cardLeft + (PROFILE_WIDTH - SKIN_WIDTH) / 2,
				skinTop + Math.max(0, (skinBottom - skinTop - skinHeight) / 2));
		addRenderableWidget(skinWidget);

		closeButton = new PanelButton(
				cardLeft + CARD_PADDING,
				cardBottom - CARD_PADDING - 20,
				PROFILE_WIDTH - CARD_PADDING * 2,
				20,
				Component.literal("Close"),
				button -> onClose());
		addRenderableWidget(closeButton);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		// NB: the blurred background is drawn for us by the framework, which
		// calls extractBackground immediately before this method. Blurring again
		// here throws "Can only blur once per frame".
		graphics.fill(0, 0, width, height, 0xC00B0E13);

		hover = null;
		drawHeader(graphics);
		drawCards(graphics, mouseX, mouseY);

		// Widgets (the skin model included) render after our fills, so they are
		// not painted over.
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);

		// Tooltip last and outside the card transform, so it is never clipped
		// or scaled with the grid.
		if (hover != null) {
			drawTierTooltip(graphics, hover, mouseX, mouseY);
		}

	}

	/** The profile card: face, name, region tag, skin model and buttons. */
	private void drawHeader(GuiGraphicsExtractor graphics) {
		Font font = this.font;

		int left = MARGIN;
		int top = MARGIN;
		drawCardFrame(graphics, left, top, left + PROFILE_WIDTH, height - MARGIN);

		int innerX = left + CARD_PADDING;
		int y = top + CARD_PADDING;

		drawFace(graphics, innerX, y);

		int nameX = innerX + FACE_SIZE + 6;
		// +1 so the text sits optically centred against the face icon.
		int nameY = y + (FACE_SIZE - font.lineHeight) / 2 + 1;
		graphics.text(font, Component.literal(playerName), nameX, nameY, 0xFFFFFFFF);

		// Region reads as a small boxed tag beside the name.
		String region = region();
		if (!region.isEmpty()) {
			drawTag(graphics, nameX + font.width(playerName) + 5, nameY - 3, region);
		}
	}

	/**
	 * A boxed region label, coloured with MCTiers' region palette (its
	 * {@code --<region>} / {@code --<region>-foreground} CSS variables).
	 */
	private void drawTag(GuiGraphicsExtractor graphics, int x, int y, String text) {
		Font font = this.font;
		int boxWidth = font.width(text) + 8;
		int boxHeight = font.lineHeight + 5;

		int foreground = regionForeground(text);
		int background = regionBackground(text);
		int border = (0xB0 << 24) | (foreground & 0xFFFFFF);

		graphics.fill(x, y, x + boxWidth, y + boxHeight, background);
		graphics.fill(x, y, x + boxWidth, y + 1, border);
		graphics.fill(x, y + boxHeight - 1, x + boxWidth, y + boxHeight, border);
		graphics.fill(x, y, x + 1, y + boxHeight, border);
		graphics.fill(x + boxWidth - 1, y, x + boxWidth, y + boxHeight, border);

		graphics.text(font, Component.literal(text), x + 4, y + 3, foreground);
	}

	private static int regionForeground(String region) {
		return switch (region) {
			case "NA" -> 0xFFD95C6A;
			case "EU" -> 0xFF89F19C;
			case "AS" -> 0xFFAF7F91;
			case "AU", "OCE" -> 0xFFD5AD80;
			case "SA" -> 0xFF5DCCDC;
			default -> 0xFFB9C4D0;
		};
	}

	private static int regionBackground(String region) {
		return switch (region) {
			case "NA" -> 0xE0442228;
			case "EU" -> 0xE01C3E20;
			case "AS" -> 0xE0422C3F;
			case "AU", "OCE" -> 0xE0392E27;
			case "SA" -> 0xE0193845;
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

		int contentLeft = MARGIN + PROFILE_WIDTH + MARGIN;
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

		// Fixed geometry: cards are the same size whether two lists load or
		// four, sized for the tallest list so nothing has to reflow.
		int cardHeight = CARD_PADDING * 2 + 18 + CARD_ROWS * ROW_HEIGHT;

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

		graphics.pose().pushMatrix();
		graphics.pose().translate(originX, originY);
		graphics.pose().scale(scale, scale);

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

		graphics.pose().popMatrix();
	}

	private void drawCard(GuiGraphicsExtractor graphics, Card card, int x, int y, int cardHeight,
			float localX, float localY) {
		Font font = this.font;

		drawCardFrame(graphics, x, y, x + CARD_WIDTH, y + cardHeight);

		int textY = y + CARD_PADDING;

		// Logo and title are centred together as one unit.
		String title = card.list().displayName();
		int headerWidth = LOGO_SIZE + 4 + font.width(title);
		int headerX = x + (CARD_WIDTH - headerWidth) / 2;

		Identifier logo = Identifier.fromNamespaceAndPath(
				SpogTiers.MOD_ID, card.list().logoPath());
		graphics.blit(RenderPipelines.GUI_TEXTURED, logo,
				headerX, textY + (font.lineHeight - LOGO_SIZE) / 2 - 2,
				0.0f, 0.0f, LOGO_SIZE, LOGO_SIZE, 64, 64, 64, 64);
		graphics.text(font, Component.literal(title),
				headerX + LOGO_SIZE + 4, textY, 0xFFFFFFFF);

		textY += font.lineHeight + 4;
		graphics.fill(x + CARD_PADDING, textY, x + CARD_WIDTH - CARD_PADDING, textY + 1, 0x28FFFFFF);
		// Extra gap so the first gamemode does not crowd the separator.
		textY += 7;

		int textX = x + CARD_PADDING;
		int widestValue = 0;
		for (Row row : card.rows()) {
			widestValue = Math.max(widestValue, font.width(row.tier().label()));
		}
		int valueX = x + CARD_WIDTH - CARD_PADDING - widestValue;

		for (Row row : card.rows()) {
			// Whole row is the hit target, not just the label.
			if (localX >= x && localX <= x + CARD_WIDTH
					&& localY >= textY - 2 && localY < textY + ROW_HEIGHT - 2) {
				hover = new Hover(card.list(), row);
			}

			int labelX = textX;
			if (row.iconKey() != null) {
				Identifier icon = Identifier.fromNamespaceAndPath(
						SpogTiers.MOD_ID, card.list().modeIconPath(row.iconKey()));
				graphics.blit(RenderPipelines.GUI_TEXTURED, icon,
						textX, textY - 3, 0.0f, 0.0f,
						MODE_ICON, MODE_ICON, 64, 64, 64, 64);
				labelX += MODE_ICON + 3;
			}
			graphics.text(font, Component.literal(row.label()), labelX, textY, row.accent());
			graphics.text(font, Component.literal(row.tier().label()), valueX, textY, row.tier().color());
			textY += ROW_HEIGHT;
		}
	}

	/** Known gamemodes in enum order, then anything the provider added. */
	private List<Row> collectRows(PlayerTiers tiers) {
		List<Row> rows = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();

		for (Gamemode mode : Gamemode.values()) {
			Tier tier = tiers.get(mode);
			if (tier.isRanked()) {
				rows.add(new Row(mode.displayName(), tier, mode.accent(), mode.key()));
				seen.add(mode.displayName());
			}
		}
		for (Map.Entry<String, Tier> entry : tiers.unknown().entrySet()) {
			if (entry.getValue().isRanked() && seen.add(entry.getKey())) {
				rows.add(new Row(entry.getKey(), entry.getValue(), LABEL_COLOR, null));
			}
		}
		return rows;
	}

	private record Row(String label, Tier tier, int accent, String iconKey) {
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

		lines.add(new Line(target.row().label() + "  " + target.row().tier().label(),
				target.row().tier().color()));

		boolean bar = false;
		if (detail.hasRating()) {
			lines.add(new Line(detail.hasTr() ? "TP " + detail.rating() : "Elo " + detail.rating(),
					0xFFE4EAF2));
			if (detail.peakRating() > 0 && detail.peakRating() != detail.rating()) {
				lines.add(new Line("Peak " + detail.peakRating(), MUTED_COLOR));
			}
			if (detail.hasNextTier()) {
				lines.add(new Line("Next: " + detail.nextTier(), MUTED_COLOR));
				bar = true;
			}
		} else if (detail.hasAttained()) {
			lines.add(new Line("Attained " + formatDate(detail.attainedSeconds()), 0xFFE4EAF2));
		} else {
			lines.add(new Line("No detail available", MUTED_COLOR));
		}

		if (target.row().tier().retired()) {
			lines.add(new Line("Retired", 0xFFD08A6A));
		}

		int textWidth = 0;
		for (Line line : lines) {
			textWidth = Math.max(textWidth, font.width(line.text()));
		}
		int boxWidth = textWidth + TOOLTIP_PADDING * 2;
		int boxHeight = TOOLTIP_PADDING * 2 + lines.size() * (font.lineHeight + 2) - 2
				+ (bar ? 8 : 0);

		// Keep the tooltip on screen rather than letting it run off an edge.
		int boxX = Math.min(mouseX + 12, width - boxWidth - 4);
		int boxY = Math.clamp(mouseY - 8, 4, height - boxHeight - 4);

		drawCardFrame(graphics, boxX, boxY, boxX + boxWidth, boxY + boxHeight);
		graphics.fill(boxX + 1, boxY + 1, boxX + boxWidth - 1, boxY + boxHeight - 1, 0xE00E1219);

		int lineY = boxY + TOOLTIP_PADDING;
		for (Line line : lines) {
			graphics.text(font, Component.literal(line.text()),
					boxX + TOOLTIP_PADDING, lineY, line.color());
			lineY += font.lineHeight + 2;
		}

		if (bar) {
			int barLeft = boxX + TOOLTIP_PADDING;
			int barRight = boxX + boxWidth - TOOLTIP_PADDING;
			int barTop = lineY;
			graphics.fill(barLeft, barTop, barRight, barTop + 4, 0xFF1B2430);
			int filled = Math.round((barRight - barLeft) * detail.progress());
			if (filled > 0) {
				graphics.fill(barLeft, barTop, barLeft + filled, barTop + 4,
						target.row().tier().color());
			}
		}
	}

	private record Line(String text, int color) {
	}

	/** Formats an epoch-seconds timestamp as a plain calendar date. */
	private static String formatDate(long epochSeconds) {
		return DATE_FORMAT.format(Instant.ofEpochSecond(epochSeconds));
	}

	private record Card(TierList list, List<Row> rows) {
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
