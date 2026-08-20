package com.spog.tiers.client.gui;

import com.spog.tiers.SpogTiersClient;
import com.spog.tiers.data.Gamemode;
import com.spog.tiers.data.PlayerTiers;
import com.spog.tiers.data.Tier;
import com.spog.tiers.data.TierList;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The frosted profile panel: skin model on the left, rankings on the right, and
 * a row of tabs for switching between tier lists.
 *
 * <p>The blur comes from {@code blurBeforeThisStratum()}, which frosts whatever
 * is already on screen; the panel fills are translucent so that blur shows
 * through instead of being painted over.
 */
public class ProfileScreen extends Screen {
	private static final int PANEL_WIDTH = 340;
	private static final int PANEL_HEIGHT = 240;
	private static final int PADDING = 12;
	private static final int ROW_HEIGHT = 17;
	private static final int TAB_HEIGHT = 18;

	private final UUID target;
	private final String playerName;
	private final AbstractClientPlayer model;

	private TierList activeList;
	private Button updateButton;
	private float animation;

	public ProfileScreen(UUID target, String playerName, AbstractClientPlayer model) {
		super(Component.literal(playerName));
		this.target = target;
		this.playerName = playerName;
		this.model = model;
		this.activeList = SpogTiersClient.config().displayList;
	}

	private int panelLeft() {
		return (width - PANEL_WIDTH) / 2;
	}

	private int panelTop() {
		return (height - PANEL_HEIGHT) / 2;
	}

	@Override
	protected void init() {
		int left = panelLeft();
		int top = panelTop();

		int tabWidth = (PANEL_WIDTH - PADDING * 2) / TierList.values().length;
		int tabX = left + PADDING;
		for (TierList list : TierList.values()) {
			TierList captured = list;
			addRenderableWidget(Button.builder(
							Component.literal(shortName(list)),
							button -> selectList(captured))
					.bounds(tabX, top + PANEL_HEIGHT - PADDING - TAB_HEIGHT, tabWidth - 2, TAB_HEIGHT)
					.build());
			tabX += tabWidth;
		}

		updateButton = addRenderableWidget(Button.builder(
						Component.literal("Update"),
						button -> refresh())
				.bounds(left + PADDING, top + PANEL_HEIGHT - PADDING - TAB_HEIGHT * 2 - 4, 70, TAB_HEIGHT)
				.build());
	}

	private void selectList(TierList list) {
		activeList = list;
		SpogTiersClient.config().displayList = list;
		SpogTiersClient.config().save();
	}

	private void refresh() {
		SpogTiersClient.service().refresh(target);
		if (updateButton != null) {
			updateButton.active = false;
		}
	}

	/** Tab labels have to fit a quarter of the panel, so abbreviate. */
	private static String shortName(TierList list) {
		return switch (list) {
			case PVPHQ -> "PVPHQ";
			case PVPTIERS -> "PvPTiers";
			case SUBTIERS -> "SubTiers";
			case MCTIERS -> "MCTiers";
		};
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		animation += partialTick;

		// Frost everything behind the panel, then lay translucent fills on top.
		// Vanilla allows exactly one blur per frame and throws on a second, so a
		// screen that already blurred this frame (the title panorama does) must
		// not blur again -- we just dim instead.
		if (blurAllowed()) {
			graphics.blurBeforeThisStratum();
		}
		graphics.fill(0, 0, width, height, 0x40000000);

		int left = panelLeft();
		int top = panelTop();
		int right = left + PANEL_WIDTH;
		int bottom = top + PANEL_HEIGHT;

		drawPanel(graphics, left, top, right, bottom);
		drawSkin(graphics, left, top, mouseX, mouseY);
		drawRankings(graphics, left, top);

		super.extractRenderState(graphics, mouseX, mouseY, partialTick);

		if (updateButton != null && !SpogTiersClient.cache().isPending(target)) {
			updateButton.active = true;
		}
	}

	/**
	 * Whether this frame still has its one allowed blur. The title screen blurs
	 * its own panorama, so opening the panel over it would otherwise crash.
	 */
	private boolean blurAllowed() {
		Minecraft client = Minecraft.getInstance();
		return client.level != null;
	}

	/** Rounded-ish translucent body with a soft top highlight and a hairline border. */
	private void drawPanel(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom) {
		int alpha = SpogTiersClient.config().panelOpacity;
		int body = (alpha << 24) | 0x0E1116;
		int sheen = ((alpha / 3) << 24) | 0x6E8399;

		// Clip the corners by insetting the first and last rows by a pixel.
		graphics.fill(left + 1, top, right - 1, top + 1, body);
		graphics.fill(left, top + 1, right, bottom - 1, body);
		graphics.fill(left + 1, bottom - 1, right - 1, bottom, body);

		graphics.fillGradient(left, top + 1, right, top + 40, sheen, 0x00000000);

		int border = ((Math.min(255, alpha + 40)) << 24) | 0x3C4654;
		graphics.fill(left, top + 1, left + 1, bottom - 1, border);
		graphics.fill(right - 1, top + 1, right, bottom - 1, border);
		graphics.fill(left + 1, top, right - 1, top + 1, border);
		graphics.fill(left + 1, bottom - 1, right - 1, bottom, border);
	}

	private void drawSkin(GuiGraphicsExtractor graphics, int left, int top, int mouseX, int mouseY) {
		Font font = this.font;
		int centreX = left + PADDING + 46;

		graphics.centeredText(font, Component.literal(playerName), centreX, top + PADDING, 0xFFFFFFFF);

		int boxTop = top + PADDING + 14;
		int boxBottom = boxTop + 116;
		graphics.fill(left + PADDING, boxTop, left + PADDING + 92, boxBottom, 0x30000000);

		// No model when opened outside a world (or for an offline player); the
		// framed box still renders so the layout does not jump.
		if (model == null) {
			graphics.centeredText(font, Component.literal("?"), centreX, boxTop + 52, 0xFF6C7683);
			return;
		}

		// Idle spin, or follow the cursor when the user disables rotation.
		float yaw = SpogTiersClient.config().rotateSkin
				? animation * 0.6f
				: (centreX - mouseX) * 0.5f;
		float pitch = SpogTiersClient.config().rotateSkin
				? 0.0f
				: (boxTop + 58 - mouseY) * 0.3f;

		InventoryScreen.extractEntityInInventoryFollowsMouse(
				graphics,
				left + PADDING, boxTop, left + PADDING + 92, boxBottom,
				42,
				0.0625f,
				yaw,
				Mth.clamp(pitch, -25.0f, 25.0f),
				model);
	}

	private void drawRankings(GuiGraphicsExtractor graphics, int left, int top) {
		Font font = this.font;
		int x = left + PADDING + 104;
		int y = top + PADDING;
		int labelColor = 0xFFB9C4D0;

		PlayerTiers tiers = SpogTiersClient.cache().get(target, activeList);

		graphics.text(font, Component.literal(activeList.displayName()), x, y, 0xFFFFFFFF);
		y += ROW_HEIGHT;

		if (SpogTiersClient.cache().isPending(target)) {
			graphics.text(font, Component.literal("Loading..."), x, y, labelColor);
			return;
		}
		if (tiers == null) {
			graphics.text(font, Component.literal("No data"), x, y, labelColor);
			return;
		}

		String region = tiers.region().isEmpty() ? "-" : tiers.region();
		graphics.text(font, Component.literal("Region"), x, y, labelColor);
		graphics.text(font, Component.literal(region), x + 110, y, 0xFF6FA8FF);
		y += ROW_HEIGHT;

		String overall = tiers.overall() > 0 ? "#" + tiers.overall() : "-";
		graphics.text(font, Component.literal("Overall"), x, y, labelColor);
		graphics.text(font, Component.literal(overall), x + 110, y, 0xFFFFA23F);
		y += ROW_HEIGHT + 4;

		List<Row> rows = collectRows(tiers);
		if (rows.isEmpty()) {
			graphics.text(font, Component.literal("Unranked"), x, y, 0xFF8A93A0);
			return;
		}

		int limit = (PANEL_HEIGHT - (y - top) - TAB_HEIGHT * 2 - PADDING * 2) / ROW_HEIGHT;
		for (int i = 0; i < Math.min(rows.size(), limit); i++) {
			Row row = rows.get(i);
			graphics.text(font, Component.literal(row.label()), x, y, row.accent());
			graphics.text(font, Component.literal(row.tier().label()), x + 110, y, row.tier().color());
			y += ROW_HEIGHT;
		}

		int hidden = rows.size() - Math.min(rows.size(), limit);
		if (hidden > 0) {
			graphics.text(font, Component.literal("+" + hidden + " more"), x, y, 0xFF6C7683);
		}
	}

	/** Known gamemodes first (enum order), then anything the provider added. */
	private List<Row> collectRows(PlayerTiers tiers) {
		List<Row> rows = new ArrayList<>();
		for (Gamemode mode : Gamemode.values()) {
			Tier tier = tiers.get(mode);
			if (tier.isRanked()) {
				rows.add(new Row(mode.displayName(), tier, mode.accent()));
			}
		}
		for (Map.Entry<String, Tier> entry : tiers.unknown().entrySet()) {
			if (entry.getValue().isRanked()) {
				rows.add(new Row(entry.getKey(), entry.getValue(), 0xFFB9C4D0));
			}
		}
		return rows;
	}

	private record Row(String label, Tier tier, int accent) {
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
