package dev.spog.tiers.client.gui;

import com.mojang.authlib.GameProfile;
import dev.spog.tiers.data.DoorTierlist;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The Door SMP tierlist as a grid: one coloured row per tier, players' faces
 * laid out along it.
 *
 * <p>The same picture the Discord bot's {@code /tierlist} posts, drawn here
 * with the game's own renderer rather than fetched as an image -- so it stays
 * sharp at any GUI scale and the server pays nothing per view. The layout
 * constants are the backend's, so the two read alike.
 *
 * <p>Retired players are left out, as they are on Discord: the picture is about
 * who is currently ranked.
 */
public class TierlistScreen extends Screen {

	/** Each face cell, and the gap between cells. The backend's proportions. */
	private static final int FACE = 24;
	private static final int GAP = 2;
	/** Width of the coloured tier label down the left. */
	private static final int LABEL_WIDTH = 42;
	private static final int PADDING = 6;
	/** Faces per line before wrapping within the same tier. */
	private static final int PER_ROW = 12;

	private static final int ROW_FILL = 0xFF1E2126;
	private static final int GRID = 0xFF2C3138;
	private static final int LABEL_TEXT = 0xFF111111;
	private static final int LABEL_TEXT_LIGHT = 0xFFFFFFFF;
	private static final int MUTED = 0xFF8A94A6;

	/**
	 * The backdrop: dark grey, and let through just enough to keep the world
	 * visible behind it. The same wash the profile screen uses.
	 */
	private static final int BACKDROP = 0xC00B0E13;

	private final Screen parent;

	/**
	 * The player named on the command, picked out in the grid, or null.
	 *
	 * <p>Not a filter: where someone stands is only legible beside everyone
	 * else, so the whole list is still drawn and their face is ringed.
	 */
	private final String highlight;

	/**
	 * Skin lookups, one per player, kept for the life of the screen.
	 *
	 * <p>A lookup serves a default skin until Mojang answers, so a face is
	 * never missing -- it only starts as Steve and becomes itself.
	 */
	private final Map<UUID, Supplier<PlayerSkin>> skins = new HashMap<>();

	private DoorTierlist.Snapshot snapshot;
	private int scroll;
	private int contentHeight;

	public TierlistScreen(Screen parent) {
		this(parent, null);
	}

	public TierlistScreen(Screen parent, String highlight) {
		super(Component.literal("Door SMP Tierlist"));
		this.parent = parent;
		this.highlight = highlight;
	}

	@Override
	protected void init() {
		snapshot = DoorTierlist.snapshot();
		DoorTierlist.request();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			float partialTick) {
		graphics.fill(0, 0, width, height, BACKDROP);

		// Re-read every frame: the fetch finishes on a background thread, and
		// this is what turns "Loading" into the grid without a tick hook.
		snapshot = DoorTierlist.snapshot();

		Font font = this.font;
		graphics.text(font, Component.literal("Door SMP Tierlist"),
				PADDING + 2, PADDING + 2, 0xFFFFFFFF);

		int top = PADDING + font.lineHeight + 8;
		if (snapshot == null) {
			graphics.text(font, Component.literal(DoorTierlist.failed()
							? "Could not reach the tierlist"
							: "Loading..."),
					PADDING + 2, top, MUTED);
			return;
		}

		List<DoorTierlist.Row> rows = snapshot.rows();
		if (rows.isEmpty()) {
			graphics.text(font, Component.literal("Nobody is on the tierlist yet"),
					PADDING + 2, top, MUTED);
			return;
		}

		// Measured before drawing so the scroll can be clamped to it, and so a
		// list taller than the window does not simply run off the bottom.
		contentHeight = 0;
		for (DoorTierlist.Row row : rows) {
			contentHeight += rowHeight(row);
		}
		int visible = height - top - PADDING;
		scroll = Math.max(0, Math.min(scroll, Math.max(0, contentHeight - visible)));

		int gridWidth = LABEL_WIDTH + PER_ROW * (FACE + GAP) + GAP;
		int x = Math.max(PADDING, (width - gridWidth) / 2);

		graphics.enableScissor(0, top, width, height - PADDING);
		int y = top - scroll;
		for (DoorTierlist.Row row : rows) {
			int rowHeight = rowHeight(row);
			// Skipped rather than drawn and clipped: a row well off screen
			// still costs a face blit per player otherwise.
			if (y + rowHeight >= top && y <= height) {
				drawRow(graphics, font, row, x, y, gridWidth, rowHeight);
			}
			y += rowHeight;
		}
		graphics.disableScissor();

		if (contentHeight > visible) {
			graphics.text(font, Component.literal("Scroll for more"),
					width - PADDING - font.width("Scroll for more"),
					height - PADDING - font.lineHeight, MUTED);
		}
	}

	/** How tall one tier's row is, given how many lines its players need. */
	private static int rowHeight(DoorTierlist.Row row) {
		int lines = Math.max(1, (row.players().size() + PER_ROW - 1) / PER_ROW);
		return lines * FACE + (lines + 1) * GAP;
	}

	private void drawRow(GuiGraphicsExtractor graphics, Font font, DoorTierlist.Row row,
			int x, int y, int width, int height) {
		// The label block, in the tier's own colour.
		graphics.fill(x, y, x + LABEL_WIDTH, y + height, 0xFF000000 | row.colour());
		graphics.fill(x + LABEL_WIDTH, y, x + width, y + height, ROW_FILL);

		// The frame and the divider between label and faces.
		graphics.fill(x, y, x + width, y + 1, GRID);
		graphics.fill(x, y + height - 1, x + width, y + height, GRID);
		graphics.fill(x, y, x + 1, y + height, GRID);
		graphics.fill(x + width - 1, y, x + width, y + height, GRID);
		graphics.fill(x + LABEL_WIDTH, y, x + LABEL_WIDTH + 1, y + height, GRID);

		String label = row.label();
		graphics.text(font, Component.literal(label),
				x + (LABEL_WIDTH - font.width(label)) / 2,
				y + (height - font.lineHeight) / 2,
				labelInk(row.colour()));

		int faceX = x + LABEL_WIDTH + GAP;
		int faceY = y + GAP;
		int column = 0;
		for (DoorTierlist.Player player : row.players()) {
			if (column == PER_ROW) {
				column = 0;
				faceX = x + LABEL_WIDTH + GAP;
				faceY += FACE + GAP;
			}
			drawFace(graphics, player, faceX, faceY);
			if (highlight != null && player.name().equalsIgnoreCase(highlight)) {
				ring(graphics, faceX - 1, faceY - 1, faceX + FACE + 1, faceY + FACE + 1);
			}
			faceX += FACE + GAP;
			column++;
		}
	}

	/**
	 * One player's face, base layer then hat.
	 *
	 * <p>A cell is filled behind it either way, so a face still loading or
	 * failing to load leaves a gap in the row rather than shifting everyone
	 * after it along.
	 */
	private void drawFace(GuiGraphicsExtractor graphics, DoorTierlist.Player player,
			int x, int y) {
		graphics.fill(x, y, x + FACE, y + FACE, GRID);

		Supplier<PlayerSkin> lookup = skins.computeIfAbsent(player.uuid(), id ->
				// secureOnly false: the session server hands back unsigned
				// texture properties, and with true every looked-up player
				// renders as the default skin.
				minecraft.getSkinManager().createLookup(
						new GameProfile(id, player.name()), false));
		PlayerSkin skin = lookup.get();
		if (skin == null) {
			return;
		}
		graphics.blit(RenderPipelines.GUI_TEXTURED, skin.body().texturePath(),
				x, y, 8.0f, 8.0f, FACE, FACE, 8, 8, 64, 64);
		graphics.blit(RenderPipelines.GUI_TEXTURED, skin.body().texturePath(),
				x, y, 40.0f, 8.0f, FACE, FACE, 8, 8, 64, 64);
	}

	/**
	 * A one-pixel outline just outside a face, marking the named player.
	 *
	 * <p>Four edges rather than a filled box, so the face underneath stays
	 * visible.
	 */
	private static void ring(GuiGraphicsExtractor graphics, int left, int top,
			int right, int bottom) {
		graphics.fill(left, top, right, top + 1, 0xFFFFFFFF);
		graphics.fill(left, bottom - 1, right, bottom, 0xFFFFFFFF);
		graphics.fill(left, top, left + 1, bottom, 0xFFFFFFFF);
		graphics.fill(right - 1, top, right, bottom, 0xFFFFFFFF);
	}

	/**
	 * Which ink reads on a tier's colour.
	 *
	 * <p>The tier colours run from near-white to a dark brown, so no single ink
	 * stays legible across all of them. Decided by perceived brightness, as the
	 * backend decides it.
	 */
	private static int labelInk(int colour) {
		int r = (colour >> 16) & 0xFF;
		int g = (colour >> 8) & 0xFF;
		int b = colour & 0xFF;
		double luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
		return luminance > 0.55 ? LABEL_TEXT : LABEL_TEXT_LIGHT;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
		scroll -= (int) (deltaY * 16);
		return true;
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	/** The world keeps running behind it, as it does behind the profile screen. */
	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
