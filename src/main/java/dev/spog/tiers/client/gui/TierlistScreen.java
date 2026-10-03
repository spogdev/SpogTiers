package dev.spog.tiers.client.gui;

import com.mojang.authlib.GameProfile;
import dev.spog.tiers.client.ClientCommands;
import dev.spog.tiers.data.DoorTierlist;
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
import java.util.concurrent.CompletableFuture;
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
 * <p>A window sized to the list and centred on screen, inside a translucent
 * frame with room for buttons along the bottom.
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
	/** Faces per line before wrapping within the same tier. */
	private static final int PER_ROW = 12;

	/** The frame's padding around the grid, and the strip the buttons sit in. */
	private static final int FRAME_PADDING = 8;
	private static final int BUTTON_STRIP = 24;
	private static final int BUTTON_WIDTH = 60;
	private static final int BUTTON_HEIGHT = 16;

	/**
	 * The row fill, let through a little so the window reads as a panel over
	 * the world rather than a solid block of its own.
	 */
	private static final int ROW_FILL = 0xC01E2126;
	private static final int GRID = 0xFF2C3138;
	/** The frame around the grid: darker than the rows, and also translucent. */
	private static final int FRAME_FILL = 0xB00B0E13;
	private static final int LABEL_TEXT = 0xFF111111;
	private static final int LABEL_TEXT_LIGHT = 0xFFFFFFFF;
	private static final int MUTED = 0xFF8A94A6;
	private static final int TOOLTIP_PADDING = 4;

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
	 * <p>Filled only once a textured profile has arrived. A profile built from
	 * an id and a name alone carries no textures property, and the skin manager
	 * reads skins from exactly that -- so a lookup made from a bare profile
	 * renders every player as the default skin.
	 */
	private final Map<UUID, Supplier<PlayerSkin>> skins = new HashMap<>();

	/** Players whose textured profile is in flight, so it is asked for once. */
	private final Map<UUID, Boolean> fetching = new HashMap<>();

	private DoorTierlist.Snapshot snapshot;
	private int scroll;

	/** Set while drawing the grid, read after it so the box is not clipped. */
	private String hoveredName;

	private PanelButton closeButton;

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

		// Positioned in the draw rather than here: where it goes depends on the
		// frame, and the frame's size depends on a roster that may not have
		// arrived yet.
		closeButton = addRenderableWidget(new PanelButton(0, 0, BUTTON_WIDTH, BUTTON_HEIGHT,
				Component.literal("Close"), button -> onClose()));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			float partialTick) {
		// Re-read every frame: the fetch finishes on a background thread, and
		// this is what turns "Loading" into the grid without a tick hook.
		snapshot = DoorTierlist.snapshot();
		hoveredName = null;

		Font font = this.font;
		List<DoorTierlist.Row> rows = snapshot == null ? List.of() : snapshot.rows();

		int gridWidth = LABEL_WIDTH + PER_ROW * (FACE + GAP) + GAP;
		int gridHeight = 0;
		for (DoorTierlist.Row row : rows) {
			gridHeight += rowHeight(row);
		}

		String message = snapshot == null
				? (DoorTierlist.failed() ? "Could not reach the tierlist" : "Loading...")
				: (rows.isEmpty() ? "Nobody is on the tierlist yet" : null);
		if (message != null) {
			// Enough window to hold the line, so the frame does not collapse to
			// nothing while the roster is on its way.
			gridWidth = Math.max(font.width(message) + 16, 160);
			gridHeight = font.lineHeight + 8;
		}

		// The window never grows past the screen: a long list scrolls inside a
		// frame that still fits rather than running off the top and bottom.
		int chromeHeight = font.lineHeight + 6 + FRAME_PADDING * 2 + BUTTON_STRIP;
		int shownHeight = Math.max(FACE, Math.min(gridHeight, height - chromeHeight - 8));

		int frameWidth = gridWidth + FRAME_PADDING * 2;
		int frameHeight = shownHeight + chromeHeight;
		int frameX = (width - frameWidth) / 2;
		int frameY = (height - frameHeight) / 2;

		// The frame itself: translucent, so the world stays visible behind it.
		graphics.fill(frameX, frameY, frameX + frameWidth, frameY + frameHeight, FRAME_FILL);
		outline(graphics, frameX, frameY, frameX + frameWidth, frameY + frameHeight, GRID);

		int x = frameX + FRAME_PADDING;
		int y = frameY + FRAME_PADDING;
		graphics.text(font, Component.literal("Door SMP Tierlist"), x, y, 0xFFFFFFFF);
		y += font.lineHeight + 6;

		if (message != null) {
			graphics.text(font, Component.literal(message), x, y, MUTED);
		} else {
			scroll = Math.max(0, Math.min(scroll, gridHeight - shownHeight));

			graphics.enableScissor(x, y, x + gridWidth, y + shownHeight);
			int rowY = y - scroll;
			for (DoorTierlist.Row row : rows) {
				int rowHeight = rowHeight(row);
				// Skipped rather than drawn and clipped: a row off screen still
				// costs a face blit per player otherwise.
				if (rowY + rowHeight >= y && rowY <= y + shownHeight) {
					drawRow(graphics, font, row, x, rowY, gridWidth, rowHeight,
							mouseX, mouseY, y, y + shownHeight);
				}
				rowY += rowHeight;
			}
			graphics.disableScissor();

			if (gridHeight > shownHeight) {
				String hint = "Scroll for more";
				graphics.text(font, Component.literal(hint),
						x + gridWidth - font.width(hint),
						frameY + frameHeight - FRAME_PADDING - font.lineHeight, MUTED);
			}
		}

		// The button strip along the bottom of the frame.
		if (closeButton != null) {
			closeButton.setX(x);
			closeButton.setY(frameY + frameHeight - FRAME_PADDING - BUTTON_HEIGHT);
		}
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);

		// Last, and outside the scissor, so the box is never clipped by the
		// grid the face belongs to.
		if (hoveredName != null) {
			drawNameTooltip(graphics, hoveredName, mouseX, mouseY);
		}
	}

	/** How tall one tier's row is, given how many lines its players need. */
	private static int rowHeight(DoorTierlist.Row row) {
		int lines = Math.max(1, (row.players().size() + PER_ROW - 1) / PER_ROW);
		return lines * FACE + (lines + 1) * GAP;
	}

	private void drawRow(GuiGraphicsExtractor graphics, Font font, DoorTierlist.Row row,
			int x, int y, int width, int height, int mouseX, int mouseY,
			int clipTop, int clipBottom) {
		// The label keeps its colour at full strength: it is the one part of a
		// row that says which tier it is, and dimming it made the palette hard
		// to tell apart.
		graphics.fill(x, y, x + LABEL_WIDTH, y + height, 0xFF000000 | row.colour());
		graphics.fill(x + LABEL_WIDTH, y, x + width, y + height, ROW_FILL);

		outline(graphics, x, y, x + width, y + height, GRID);
		graphics.fill(x + LABEL_WIDTH, y, x + LABEL_WIDTH + 1, y + height, GRID);

		String label = row.label();
		// Drawn without a shadow: the label sits on its own bright colour,
		// where a shadow reads as a smudge rather than as depth.
		graphics.text(font, Component.literal(label),
				x + (LABEL_WIDTH - font.width(label)) / 2,
				y + (height - font.lineHeight) / 2,
				labelInk(row.colour()), false);

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
				outline(graphics, faceX - 1, faceY - 1, faceX + FACE + 1, faceY + FACE + 1,
						0xFFFFFFFF);
			}
			// Bounded by the clip as well as the cell: a face scrolled out of
			// view keeps the coordinates it was drawn at, and must not answer
			// the pointer.
			if (mouseX >= faceX && mouseX < faceX + FACE
					&& mouseY >= faceY && mouseY < faceY + FACE
					&& mouseY >= clipTop && mouseY < clipBottom) {
				hoveredName = player.name();
				outline(graphics, faceX - 1, faceY - 1, faceX + FACE + 1, faceY + FACE + 1,
						0x80FFFFFF);
			}
			faceX += FACE + GAP;
			column++;
		}
	}

	/**
	 * One player's face, base layer then hat.
	 *
	 * <p>A cell is filled behind it either way, so a face still loading leaves
	 * a gap in the row rather than shifting everyone after it along.
	 */
	private void drawFace(GuiGraphicsExtractor graphics, DoorTierlist.Player player,
			int x, int y) {
		graphics.fill(x, y, x + FACE, y + FACE, GRID);

		Supplier<PlayerSkin> lookup = skins.get(player.uuid());
		if (lookup == null) {
			requestSkin(player);
			return;
		}
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
	 * Fetches the player's textured profile, then starts a skin lookup on it.
	 *
	 * <p>Two steps rather than one because the roster gives only an id and a
	 * name. The session server is what carries the textures property, and the
	 * skin manager reads skins from that property alone -- so a lookup made
	 * from a bare profile renders as the default skin, which is what every face
	 * here used to be. The fetch blocks, so it runs off the render thread and
	 * the lookup is registered back on it.
	 */
	private void requestSkin(DoorTierlist.Player player) {
		if (fetching.putIfAbsent(player.uuid(), Boolean.TRUE) != null) {
			return;
		}
		CompletableFuture
				.supplyAsync(() -> ClientCommands.texturedProfile(
						player.uuid(), player.name()))
				.thenAcceptAsync(profile -> {
					// A failed texture fetch still gets a lookup, so the cell
					// shows a default head rather than staying empty.
					GameProfile resolved = profile != null
							? profile
							: new GameProfile(player.uuid(), player.name());
					// secureOnly false: the session server hands back unsigned
					// texture properties, and with true every looked-up player
					// renders as the default skin.
					skins.put(player.uuid(),
							minecraft.getSkinManager().createLookup(resolved, false));
				}, minecraft);
	}

	/** The hovered player's name, beside the pointer. */
	private void drawNameTooltip(GuiGraphicsExtractor graphics, String name,
			int mouseX, int mouseY) {
		Font font = this.font;
		int boxWidth = font.width(name) + TOOLTIP_PADDING * 2;
		int boxHeight = font.lineHeight + TOOLTIP_PADDING * 2;
		int boxX = Tooltips.x(mouseX, boxWidth, width);
		int boxY = Math.clamp(mouseY - 8, 4, Math.max(4, height - boxHeight - 4));

		graphics.fill(boxX, boxY, boxX + boxWidth, boxY + boxHeight, 0xE00E1219);
		outline(graphics, boxX, boxY, boxX + boxWidth, boxY + boxHeight, GRID);
		graphics.text(font, Component.literal(name),
				boxX + TOOLTIP_PADDING, boxY + TOOLTIP_PADDING, 0xFFE4EAF2);
	}

	/**
	 * A one-pixel border, drawn inside the given bounds.
	 *
	 * <p>Four edges rather than a filled box, so whatever it marks stays
	 * visible underneath it.
	 */
	private static void outline(GuiGraphicsExtractor graphics, int left, int top,
			int right, int bottom, int colour) {
		graphics.fill(left, top, right, top + 1, colour);
		graphics.fill(left, bottom - 1, right, bottom, colour);
		graphics.fill(left, top, left + 1, bottom, colour);
		graphics.fill(right - 1, top, right, bottom, colour);
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
		minecraft.setScreen(parent);
	}

	/** The world keeps running behind it, as it does behind the profile screen. */
	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
