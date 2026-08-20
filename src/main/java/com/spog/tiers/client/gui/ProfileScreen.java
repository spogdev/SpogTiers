package com.spog.tiers.client.gui;

import com.mojang.authlib.GameProfile;
import com.spog.tiers.SpogTiersClient;
import com.spog.tiers.data.Gamemode;
import com.spog.tiers.data.PlayerTiers;
import com.spog.tiers.data.Tier;
import com.spog.tiers.data.TierList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerSkinWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Full-screen profile view: the player skin on the left, and one card per tier
 * list they are actually ranked in, laid out in up to two rows and centred.
 *
 * <p>The skin is rendered from the player GameProfile rather than from an
 * entity, so it works for anyone -- including players not on the current
 * server, or when not connected to one at all. The profile must carry its
 * {@code textures} property or the model falls back to the default skin.
 */
public class ProfileScreen extends Screen {
	private static final int MARGIN = 24;
	private static final int ROW_HEIGHT = 14;
	private static final int FACE_SIZE = 20;
	private static final int PROFILE_WIDTH = 150;
	private static final int SKIN_WIDTH = 90;
	private static final int SKIN_HEIGHT = 130;
	private static final int CARD_PADDING = 10;
	private static final int CARD_GAP = 10;
	private static final int MAX_CARD_ROWS = 2;
	private static final int LABEL_COLOR = 0xFFB9C4D0;
	private static final int MUTED_COLOR = 0xFF6C7683;
	private static final int CARD_FILL = 0x50161B22;
	private static final int CARD_BORDER = 0x70323B47;

	private final UUID target;
	private final String playerName;
	private final GameProfile profile;

	private Supplier<PlayerSkin> skin;
	private Button updateButton;

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
		int buttonWidth = (PROFILE_WIDTH - CARD_PADDING * 2 - 6) / 2;

		PlayerSkinWidget skinWidget = new PlayerSkinWidget(
				SKIN_WIDTH, SKIN_HEIGHT, client.getEntityModels(), skin);
		skinWidget.setPosition(
				cardLeft + (PROFILE_WIDTH - SKIN_WIDTH) / 2,
				cardTop + CARD_PADDING + FACE_SIZE + 30);
		addRenderableWidget(skinWidget);

		updateButton = addRenderableWidget(Button.builder(
						Component.literal("Update"),
						button -> refresh())
				.bounds(cardLeft + CARD_PADDING, cardBottom - CARD_PADDING - 20, buttonWidth, 20)
				.build());

		addRenderableWidget(Button.builder(
						Component.literal("Close"),
						button -> onClose())
				.bounds(cardLeft + CARD_PADDING + buttonWidth + 6,
						cardBottom - CARD_PADDING - 20, buttonWidth, 20)
				.build());
	}

	private void refresh() {
		SpogTiersClient.service().refresh(target);
		if (updateButton != null) {
			updateButton.active = false;
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		// NB: the blurred background is drawn for us by the framework, which
		// calls extractBackground immediately before this method. Blurring again
		// here throws "Can only blur once per frame".
		graphics.fill(0, 0, width, height, 0xC00B0E13);

		drawHeader(graphics);
		drawCards(graphics);

		// Widgets (the skin model included) render after our fills, so they are
		// not painted over.
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);

		if (updateButton != null && !SpogTiersClient.cache().isPending(target)) {
			updateButton.active = true;
		}
	}

	/** The profile card: face, name, region/rank, skin model and buttons. */
	private void drawHeader(GuiGraphicsExtractor graphics) {
		Font font = this.font;

		int left = MARGIN;
		int top = MARGIN;
		int right = left + PROFILE_WIDTH;
		int bottom = height - MARGIN;
		drawCardFrame(graphics, left, top, right, bottom);

		int innerX = left + CARD_PADDING;
		int y = top + CARD_PADDING;

		drawFace(graphics, innerX, y);
		graphics.text(font, Component.literal(playerName),
				innerX + FACE_SIZE + 6, y + (FACE_SIZE - font.lineHeight) / 2, 0xFFFFFFFF);
		y += FACE_SIZE + 8;

		// Region and rank on their own lines so nothing clips into the name.
		if (SpogTiersClient.cache().isPending(target)) {
			graphics.text(font, Component.literal("Loading..."), innerX, y, MUTED_COLOR);
			return;
		}
		for (String line : summaryLines()) {
			graphics.text(font, Component.literal(line), innerX, y, MUTED_COLOR);
			y += font.lineHeight + 2;
		}
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

	/** Region and overall rank, taken from whichever list reports them. */
	private List<String> summaryLines() {
		Map<TierList, PlayerTiers> all = SpogTiersClient.cache().allLists(target);
		List<String> lines = new ArrayList<>();
		for (TierList list : TierList.values()) {
			PlayerTiers tiers = all.get(list);
			if (tiers != null && !tiers.region().isEmpty()) {
				lines.add("Region: " + tiers.region());
				if (tiers.overall() > 0) {
					lines.add("Overall: #" + tiers.overall());
				}
				lines.add("via " + list.displayName());
				return lines;
			}
		}
		if (all.isEmpty()) {
			lines.add("No rankings");
		}
		return lines;
	}

	/** One card per ranked list, wrapped over at most {@value #MAX_CARD_ROWS} rows. */
	private void drawCards(GuiGraphicsExtractor graphics) {
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
					contentTop + 20,
					MUTED_COLOR);
			return;
		}

		int cardWidth = 0;
		for (Card card : cards) {
			cardWidth = Math.max(cardWidth, card.width(font));
		}
		cardWidth += CARD_PADDING * 2;

		// Prefer a balanced grid over a full first row: 4 cards read better as
		// 2x2 than 3+1, and 3 stay on one row.
		int perRow = switch (cards.size()) {
			case 1 -> 1;
			case 2 -> 2;
			case 3 -> 3;
			default -> (cards.size() + 1) / 2;
		};
		int rowCount = (cards.size() + perRow - 1) / perRow;

		// Each row is only as tall as its own tallest card, so a short row does
		// not inherit a tall one's height.
		int[] rowHeights = new int[rowCount];
		for (int index = 0; index < cards.size(); index++) {
			rowHeights[index / perRow] =
					Math.max(rowHeights[index / perRow], cards.get(index).height());
		}
		// A grid reads as a grid only if its cells line up, so give every card
		// in a row the same height.

		int gaps = (rowCount - 1) * CARD_GAP;
		int blockHeight = gaps;
		for (int rowHeight : rowHeights) {
			blockHeight += rowHeight;
		}
		int blockWidth = perRow * cardWidth + (perRow - 1) * CARD_GAP;

		// Scale the block to fill the available area instead of dropping rows,
		// so every ranking stays visible and the cards use the whole panel.
		int availableHeight = contentBottom - contentTop;
		float scale = Math.min(
				(float) contentWidth / blockWidth,
				(float) availableHeight / blockHeight);
		scale = Math.clamp(scale, 0.5f, 2.0f);

		int scaledWidth = Math.round(blockWidth * scale);
		int scaledHeight = Math.round(blockHeight * scale);
		int originX = contentLeft + (contentWidth - scaledWidth) / 2;
		int originY = contentTop + Math.max(0, (availableHeight - scaledHeight) / 2);

		graphics.pose().pushMatrix();
		graphics.pose().translate(originX, originY);
		graphics.pose().scale(scale, scale);

		int y = 0;
		for (int row = 0; row < rowCount; row++) {
			int inThisRow = Math.min(perRow, cards.size() - row * perRow);
			int rowWidth = inThisRow * cardWidth + (inThisRow - 1) * CARD_GAP;
			int rowLeft = (blockWidth - rowWidth) / 2;

			for (int column = 0; column < inThisRow; column++) {
				Card card = cards.get(row * perRow + column);
				int x = rowLeft + column * (cardWidth + CARD_GAP);
				drawCard(graphics, card, x, y, cardWidth, rowHeights[row]);
			}
			y += rowHeights[row] + CARD_GAP;
		}

		graphics.pose().popMatrix();
	}

	private void drawCard(GuiGraphicsExtractor graphics, Card card, int x, int y, int cardWidth, int cardHeight) {
		Font font = this.font;

		graphics.fill(x, y, x + cardWidth, y + cardHeight, CARD_FILL);
		graphics.fill(x, y, x + cardWidth, y + 1, CARD_BORDER);
		graphics.fill(x, y + cardHeight - 1, x + cardWidth, y + cardHeight, CARD_BORDER);
		graphics.fill(x, y, x + 1, y + cardHeight, CARD_BORDER);
		graphics.fill(x + cardWidth - 1, y, x + cardWidth, y + cardHeight, CARD_BORDER);

		int textX = x + CARD_PADDING;
		int textY = y + CARD_PADDING;

		String title = card.list().displayName();
		graphics.text(font, Component.literal(title),
				x + (cardWidth - font.width(title)) / 2, textY, 0xFFFFFFFF);
		textY += font.lineHeight + 5;

		graphics.fill(x + CARD_PADDING, textY - 3, x + cardWidth - CARD_PADDING, textY - 2, 0x28FFFFFF);

		int widestValue = 0;
		for (Row row : card.rows()) {
			widestValue = Math.max(widestValue, font.width(row.tier().label()));
		}
		int valueX = x + cardWidth - CARD_PADDING - widestValue;

		for (Row row : card.rows()) {
			graphics.text(font, Component.literal(row.label()), textX, textY, row.accent());
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
				rows.add(new Row(mode.displayName(), tier, mode.accent()));
				seen.add(mode.displayName());
			}
		}
		for (Map.Entry<String, Tier> entry : tiers.unknown().entrySet()) {
			if (entry.getValue().isRanked() && seen.add(entry.getKey())) {
				rows.add(new Row(entry.getKey(), entry.getValue(), LABEL_COLOR));
			}
		}
		return rows;
	}

	private record Row(String label, Tier tier, int accent) {
	}

	private record Card(TierList list, List<Row> rows) {
		/** Widest label + value pair, or the title if that is wider. */
		int width(Font font) {
			int widest = font.width(list.displayName());
			for (Row row : rows) {
				widest = Math.max(widest, font.width(row.label()) + 20 + font.width(row.tier().label()));
			}
			return widest;
		}

		int height() {
			return CARD_PADDING * 2 + 14 + rows.size() * ROW_HEIGHT;
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
