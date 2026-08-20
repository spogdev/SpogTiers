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
 * Full-screen profile view: the player skin on the left, and one column per
 * tier list they are actually ranked in, centred across the remaining space.
 *
 * <p>The skin is rendered from the player GameProfile rather than from an
 * entity, so it works for anyone -- including players not on the current
 * server, or when not connected to one at all.
 */
public class ProfileScreen extends Screen {
	private static final int MARGIN = 24;
	private static final int ROW_HEIGHT = 18;
	private static final int COLUMN_WIDTH = 150;
	private static final int SKIN_WIDTH = 130;
	private static final int FACE_SIZE = 20;
	private static final int LABEL_COLOR = 0xFFB9C4D0;
	private static final int MUTED_COLOR = 0xFF6C7683;

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
		skin = client.getSkinManager().createLookup(profile, true);

		int skinTop = MARGIN + FACE_SIZE + 22;
		int skinHeight = Math.max(80, height - skinTop - MARGIN - 28);
		PlayerSkinWidget skinWidget = new PlayerSkinWidget(
				SKIN_WIDTH, skinHeight, client.getEntityModels(), skin);
		skinWidget.setPosition(MARGIN, skinTop);
		addRenderableWidget(skinWidget);

		updateButton = addRenderableWidget(Button.builder(
						Component.literal("Update"),
						button -> refresh())
				.bounds(MARGIN, height - MARGIN - 20, 62, 20)
				.build());

		addRenderableWidget(Button.builder(
						Component.literal("Close"),
						button -> onClose())
				.bounds(MARGIN + 68, height - MARGIN - 20, 62, 20)
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
		drawColumns(graphics);

		// Widgets (the skin model included) render after our fills, so they are
		// not painted over.
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);

		if (updateButton != null && !SpogTiersClient.cache().isPending(target)) {
			updateButton.active = true;
		}
	}

	private void drawHeader(GuiGraphicsExtractor graphics) {
		Font font = this.font;

		drawFace(graphics, MARGIN, MARGIN);

		int textX = MARGIN + FACE_SIZE + 8;
		int nameY = MARGIN + (FACE_SIZE - font.lineHeight) / 2;
		graphics.text(font, Component.literal(playerName), textX, nameY, 0xFFFFFFFF);

		// Kept below the face row so it never clips into the name.
		String subtitle = SpogTiersClient.cache().isPending(target)
				? "Loading rankings..."
				: summarise();
		if (!subtitle.isEmpty()) {
			graphics.text(font, Component.literal(subtitle),
					MARGIN, MARGIN + FACE_SIZE + 7, MUTED_COLOR);
		}
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
	private String summarise() {
		Map<TierList, PlayerTiers> all = SpogTiersClient.cache().allLists(target);
		for (TierList list : TierList.values()) {
			PlayerTiers tiers = all.get(list);
			if (tiers != null && !tiers.region().isEmpty()) {
				String text = "Region: " + tiers.region();
				if (tiers.overall() > 0) {
					text += "   Overall: #" + tiers.overall();
				}
				return text + "   (" + list.displayName() + ")";
			}
		}
		return "";
	}

	/** Only lists with actual rankings, centred across the space beside the skin. */
	private void drawColumns(GuiGraphicsExtractor graphics) {
		Font font = this.font;
		Map<TierList, PlayerTiers> all = SpogTiersClient.cache().allLists(target);
		int top = MARGIN + FACE_SIZE + 28;

		List<Ranked> ranked = new ArrayList<>();
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
				ranked.add(new Ranked(list, rows));
			}
		}

		int contentLeft = MARGIN + SKIN_WIDTH + MARGIN;
		int contentWidth = Math.max(COLUMN_WIDTH, width - contentLeft - MARGIN);

		if (ranked.isEmpty()) {
			String message = SpogTiersClient.cache().isPending(target)
					? "Loading rankings..."
					: playerName + " is unranked";
			graphics.text(font, Component.literal(message),
					contentLeft + (contentWidth - font.width(message)) / 2,
					top + 20,
					MUTED_COLOR);
			return;
		}

		// Size columns to their widest row so a lone column is not padded out to
		// a fixed width, then centre the whole block. Removing unranked lists
		// therefore closes the gap instead of leaving a hole where they sat.
		int columnWidth = 0;
		for (Ranked entry : ranked) {
			for (Row row : entry.rows()) {
				columnWidth = Math.max(columnWidth,
						font.width(row.label()) + 24 + font.width(row.tier().label()));
			}
			columnWidth = Math.max(columnWidth, font.width(entry.list().displayName()));
		}
		columnWidth = Math.min(COLUMN_WIDTH, columnWidth + 20);

		int blockWidth = Math.min(contentWidth, ranked.size() * columnWidth);
		columnWidth = blockWidth / ranked.size();
		int startX = contentLeft + (contentWidth - blockWidth) / 2;

		for (int i = 0; i < ranked.size(); i++) {
			Ranked entry = ranked.get(i);
			int x = startX + i * columnWidth;
			int y = top;

			graphics.text(font, Component.literal(entry.list().displayName()), x, y, 0xFFFFFFFF);
			y += ROW_HEIGHT + 4;

			int widestValue = 0;
			for (Row row : entry.rows()) {
				widestValue = Math.max(widestValue, font.width(row.tier().label()));
			}
			int valueX = x + columnWidth - 12 - widestValue;
			int limit = Math.max(1, (height - MARGIN - 28 - y) / ROW_HEIGHT);
			List<Row> rows = entry.rows();
			for (int r = 0; r < Math.min(rows.size(), limit); r++) {
				Row row = rows.get(r);
				graphics.text(font, Component.literal(row.label()), x, y, row.accent());
				graphics.text(font, Component.literal(row.tier().label()), valueX, y, row.tier().color());
				y += ROW_HEIGHT;
			}

			int hidden = rows.size() - Math.min(rows.size(), limit);
			if (hidden > 0) {
				graphics.text(font, Component.literal("+" + hidden + " more"), x, y, MUTED_COLOR);
			}
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

	private record Ranked(TierList list, List<Row> rows) {
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
