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
 * Full-screen profile view: the player skin and name on the left, and one
 * column per tier list showing every gamemode they are ranked in.
 *
 * <p>The skin is rendered from the player GameProfile rather than from an
 * entity, so it works for anyone -- including players who are not on the
 * current server, or when not connected to one at all.
 */
public class ProfileScreen extends Screen {
	private static final int MARGIN = 24;
	private static final int ROW_HEIGHT = 18;
	private static final int HEADER_HEIGHT = 46;
	private static final int SKIN_COLUMN = 150;
	private static final int LABEL_COLOR = 0xFFB9C4D0;
	private static final int MUTED_COLOR = 0xFF6C7683;

	private final UUID target;
	private final String playerName;
	private final GameProfile profile;

	private PlayerSkinWidget skinWidget;
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

		// The target may never have been seen on this server, so the tick loop
		// will not have queued them; ask explicitly.
		SpogTiersClient.service().request(target);

		// createLookup fetches from Mojang in the background and serves a default
		// skin until it arrives, so this is safe for arbitrary profiles.
		Supplier<PlayerSkin> skin = client.getSkinManager().createLookup(profile, true);

		int skinHeight = Math.min(height - HEADER_HEIGHT - MARGIN * 2 - 28, 210);
		skinWidget = addRenderableWidget(new PlayerSkinWidget(
				SKIN_COLUMN - 30, skinHeight, client.getEntityModels(), skin));
		skinWidget.setPosition(MARGIN, HEADER_HEIGHT + 20);

		updateButton = addRenderableWidget(Button.builder(
						Component.literal("Update"),
						button -> refresh())
				.bounds(MARGIN, height - MARGIN - 20, 84, 20)
				.build());

		addRenderableWidget(Button.builder(
						Component.literal("Close"),
						button -> onClose())
				.bounds(MARGIN + 90, height - MARGIN - 20, 84, 20)
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
		// Vanilla permits exactly one blur per frame and throws on a second, so
		// only blur when nothing else already has (the title panorama does).
		if (Minecraft.getInstance().level != null) {
			graphics.blurBeforeThisStratum();
		}
		graphics.fill(0, 0, width, height, 0xC00B0E13);

		drawHeader(graphics);
		drawColumns(graphics);

		super.extractRenderState(graphics, mouseX, mouseY, partialTick);

		if (updateButton != null && !SpogTiersClient.cache().isPending(target)) {
			updateButton.active = true;
		}
	}

	private void drawHeader(GuiGraphicsExtractor graphics) {
		Font font = this.font;

		graphics.text(font, Component.literal(playerName), MARGIN, MARGIN, 0xFFFFFFFF);

		String subtitle = SpogTiersClient.cache().isPending(target)
				? "Loading rankings..."
				: summarise();
		graphics.text(font, Component.literal(subtitle), MARGIN, MARGIN + 12, MUTED_COLOR);

		graphics.fill(MARGIN, HEADER_HEIGHT - 8, width - MARGIN, HEADER_HEIGHT - 7, 0x30FFFFFF);
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
		return all.isEmpty() ? "No rankings found" : "";
	}

	/** One column per tier list, laid out across the remaining width. */
	private void drawColumns(GuiGraphicsExtractor graphics) {
		Font font = this.font;
		Map<TierList, PlayerTiers> all = SpogTiersClient.cache().allLists(target);

		TierList[] lists = TierList.values();
		int available = width - MARGIN * 2 - SKIN_COLUMN;
		int columnWidth = available / lists.length;
		int top = HEADER_HEIGHT + 8;

		for (int i = 0; i < lists.length; i++) {
			TierList list = lists[i];
			int x = MARGIN + SKIN_COLUMN + i * columnWidth;
			int y = top;

			boolean enabled = SpogTiersClient.config().isEnabled(list);
			graphics.text(font, Component.literal(list.displayName()), x, y,
					enabled ? 0xFFFFFFFF : MUTED_COLOR);
			y += 4 + ROW_HEIGHT;

			if (!enabled) {
				graphics.text(font, Component.literal("Disabled"), x, y, MUTED_COLOR);
				continue;
			}

			PlayerTiers tiers = all.get(list);
			if (tiers == null) {
				graphics.text(font, Component.literal(
						SpogTiersClient.cache().isPending(target) ? "..." : "No data"),
						x, y, MUTED_COLOR);
				continue;
			}

			List<Row> rows = collectRows(tiers);
			if (rows.isEmpty()) {
				graphics.text(font, Component.literal("Unranked"), x, y, MUTED_COLOR);
				continue;
			}

			int valueX = x + columnWidth - 52;
			int limit = Math.max(1, (height - MARGIN - 28 - y) / ROW_HEIGHT);
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

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
