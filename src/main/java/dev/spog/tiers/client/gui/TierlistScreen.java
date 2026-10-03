package dev.spog.tiers.client.gui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import dev.spog.tiers.client.ClientCommands;
import dev.spog.tiers.data.DoorTierlist;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.text.Text;
import net.minecraft.entity.player.SkinTextures;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
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
	/** The toggle says "Retired"/"Main", so it needs a little more room. */
	private static final int TOGGLE_WIDTH = 68;
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

	/**
	 * How many texture fetches may be in flight at once.
	 *
	 * <p>The session server rate-limits per address, and a full roster is
	 * dozens of players. Fetching them all at once is what made faces fail in
	 * batches; a few at a time fills the grid a little slower and reliably.
	 */
	private static final int MAX_IN_FLIGHT = 4;

	/**
	 * The threads texture fetches run on.
	 *
	 * <p>Shared across screens and daemon, so closing the tierlist does not
	 * leave work orphaned and the client can still exit. Small on purpose: see
	 * {@link #MAX_IN_FLIGHT}.
	 */
	private static final Executor SKIN_FETCHER = Executors.newFixedThreadPool(
			MAX_IN_FLIGHT, runnable -> {
				Thread thread = new Thread(runnable, "spogtiers-tierlist-skins");
				thread.setDaemon(true);
				return thread;
			});

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
	private final Map<UUID, Supplier<SkinTextures>> skins = new HashMap<>();

	/** Players whose textured profile is in flight, so it is asked for once. */
	private final Map<UUID, Boolean> fetching = new HashMap<>();

	/**
	 * When a failed fetch may be tried again, by player.
	 *
	 * <p>A failure is retried rather than cached. The alternative is leaving a
	 * player as a default head for the life of the screen, and a default head
	 * must only ever mean that is genuinely their skin.
	 */
	private final Map<UUID, Long> retryAt = new HashMap<>();

	/** How many attempts a player gets before the cell is left blank. */
	private static final int MAX_ATTEMPTS = 4;

	/** Attempts so far, by player. */
	private final Map<UUID, Integer> attempts = new HashMap<>();

	/**
	 * Players whose textures property names no skin, so the default is theirs.
	 *
	 * <p>Read off the profile rather than guessed from the resolved skin, which
	 * cannot tell "still loading" from "wears the default".
	 */
	private final Map<UUID, Boolean> wearsDefault = new HashMap<>();

	/**
	 * How long to wait before retrying a failed fetch, per attempt.
	 *
	 * <p>Backed off rather than retried immediately: the usual reason a batch
	 * fails is Mojang rate-limiting a burst of requests, and hammering it again
	 * at once is what caused the burst.
	 */
	private static final long[] RETRY_BACKOFF_MILLIS = {400L, 1_200L, 3_000L};

	/** How many fetches are running right now. */
	private int inFlight;

	/**
	 * The textured profiles themselves, kept so a click can pass one straight
	 * to the profile screen rather than making it fetch the same thing again.
	 */
	private final Map<UUID, GameProfile> profiles = new HashMap<>();

	private DoorTierlist.Snapshot snapshot;
	private int scroll;

	/** Which list is on show. The main one, until the toggle is pressed. */
	private boolean showRetired;

	/**
	 * The face under the pointer, set while drawing and read by a click.
	 *
	 * <p>Recorded rather than hit-tested again on click: the rows are laid out
	 * during the draw, and working out where a face landed a second time would
	 * be the same arithmetic in two places.
	 */
	private DoorTierlist.Player hovered;

	/** Set while drawing the grid, read after it so the box is not clipped. */
	private String hoveredName;

	/**
	 * A player whose profile was clicked before their textures had arrived.
	 *
	 * <p>Held rather than opened with what was available: see
	 * {@link #mouseClicked}. Cleared once the screen opens, or when the fetch
	 * gives up for good.
	 */
	private DoorTierlist.Player pendingOpen;

	/**
	 * The name of a player whose profile could not be opened, or null.
	 *
	 * <p>Shown under the frame instead of the waiting line, so a click that
	 * goes nowhere says why rather than appearing to be ignored.
	 */
	private String failedOpen;

	private PanelButton closeButton;
	private PanelButton toggleButton;

	public TierlistScreen(Screen parent) {
		this(parent, null);
	}

	public TierlistScreen(Screen parent, String highlight) {
		super(Text.literal("Door SMP Tierlist"));
		this.parent = parent;
		this.highlight = highlight;
	}

	@Override
	protected void init() {
		snapshot = DoorTierlist.snapshot();
		DoorTierlist.request();

		// Cleared first: init runs again every time this screen is shown, and
		// coming back from a profile would otherwise add a second pair of
		// buttons on top of the first.
		clearChildren();

		// Positioned in the draw rather than here: where it goes depends on the
		// frame, and the frame's size depends on a roster that may not have
		// arrived yet.
		closeButton = addDrawableChild(new PanelButton(0, 0, BUTTON_WIDTH, BUTTON_HEIGHT,
				Text.literal("Close"), button -> close()));
		// Labelled from the current state, not hardcoded: init runs again when
		// this screen is shown a second time -- coming back from a profile --
		// and a fresh "Retired" button over the retired list would be a lie.
		toggleButton = addDrawableChild(new PanelButton(0, 0, TOGGLE_WIDTH, BUTTON_HEIGHT,
				Text.literal(showRetired ? "Main" : "Retired"), button -> {
					showRetired = !showRetired;
					// Reset, or switching to a shorter list leaves the view
					// scrolled past the end of it.
					scroll = 0;
					button.setMessage(Text.literal(showRetired ? "Main" : "Retired"));
				}));
	}

	@Override
	public void render(DrawContext graphics, int mouseX, int mouseY,
			float partialTick) {
		// Re-read every frame: the fetch finishes on a background thread, and
		// this is what turns "Loading" into the grid without a tick hook.
		snapshot = DoorTierlist.snapshot();
		hoveredName = null;
		hovered = null;

		TextRenderer font = this.textRenderer;
		List<DoorTierlist.Row> rows =
				snapshot == null ? List.of() : snapshot.rows(showRetired);

		int gridWidth = LABEL_WIDTH + PER_ROW * (FACE + GAP) + GAP;
		int gridHeight = 0;
		for (DoorTierlist.Row row : rows) {
			gridHeight += rowHeight(row);
		}

		String message = snapshot == null
				? (DoorTierlist.failed() ? "Could not reach the tierlist" : "Loading...")
				// Asked of the players rather than the rows: every tier has a
				// row now, so an empty list is one where no row has anybody.
				: (snapshot.any(showRetired) ? null
						: (showRetired ? "Nobody has retired yet"
								: "Nobody is on the tierlist yet"));
		if (message != null) {
			// Enough window to hold the line, so the frame does not collapse to
			// nothing while the roster is on its way.
			gridWidth = Math.max(font.getWidth(message) + 16, 160);
			gridHeight = font.fontHeight + 8;
		}

		// The window never grows past the screen: a long list scrolls inside a
		// frame that still fits rather than running off the top and bottom.
		int chromeHeight = font.fontHeight + 6 + FRAME_PADDING * 2 + BUTTON_STRIP;
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
		graphics.drawTextWithShadow(font, Text.literal(showRetired
				? "Door SMP Tierlist - Retired" : "Door SMP Tierlist"),
				x, y, 0xFFFFFFFF);
		y += font.fontHeight + 6;

		if (message != null) {
			graphics.drawTextWithShadow(font, Text.literal(message), x, y, MUTED);
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
				// On the title line, not the bottom: the button strip is down
				// there now and the two would sit on top of each other.
				String hint = "Scroll for more";
				graphics.drawTextWithShadow(font, Text.literal(hint),
						x + gridWidth - font.getWidth(hint),
						frameY + FRAME_PADDING, MUTED);
			}
		}

		// The button strip along the bottom of the frame: the toggle on the
		// left, Close on the right.
		int buttonY = frameY + frameHeight - FRAME_PADDING - BUTTON_HEIGHT;
		if (toggleButton != null) {
			toggleButton.setX(x);
			toggleButton.setY(buttonY);
		}
		if (closeButton != null) {
			closeButton.setX(frameX + frameWidth - FRAME_PADDING - BUTTON_WIDTH);
			closeButton.setY(buttonY);
		}
		super.render(graphics, mouseX, mouseY, partialTick);

		// Last, and outside the scissor, so the box is never clipped by the
		// grid the face belongs to.
		if (hoveredName != null) {
			drawNameTooltip(graphics, hoveredName, mouseX, mouseY);
		}

		if (pendingOpen != null) {
			servePendingOpen(graphics, font, frameX, frameY, frameWidth, frameHeight);
		} else if (failedOpen != null) {
			String failed = "Could not load " + failedOpen + "'s skin";
			graphics.drawTextWithShadow(font, Text.literal(failed),
					frameX + (frameWidth - font.getWidth(failed)) / 2,
					frameY + frameHeight + 4, MUTED);
		}
	}

	/**
	 * Opens a click that was waiting on its textures, or says it is waiting.
	 *
	 * <p>Gives up only when the fetch has: the same attempt limit that stops a
	 * face being retried forever also stops this, so a player whose textures
	 * cannot be read does not leave the screen waiting on them.
	 */
	private void servePendingOpen(DrawContext graphics, TextRenderer font,
			int frameX, int frameY, int frameWidth, int frameHeight) {
		UUID id = pendingOpen.uuid();
		GameProfile profile = profiles.get(id);
		if (profile != null) {
			pendingOpen = null;
			open(profile);
			return;
		}
		// Not given up on while an attempt is still running: attempts is
		// counted up when a fetch starts, so on the last one this would
		// otherwise abandon the click a moment before its answer arrives.
		if (attempts.getOrDefault(id, 0) >= MAX_ATTEMPTS && !fetching.containsKey(id)) {
			// Out of attempts, so this will not arrive. Dropped rather than
			// opened with a bare profile, which would show a skin that is not
			// theirs. Said on the screen rather than in chat: the message
			// belongs to the thing that was clicked.
			failedOpen = pendingOpen.name();
			pendingOpen = null;
			return;
		}
		// Nudged here as well as by the draw, so a player scrolled out of view
		// still gets one, and urgently: a click must not wait behind faces
		// nobody asked for.
		requestSkin(pendingOpen, true);

		String waiting = "Opening " + pendingOpen.name() + "...";
		graphics.drawTextWithShadow(font, Text.literal(waiting),
				frameX + (frameWidth - font.getWidth(waiting)) / 2,
				frameY + frameHeight + 4, MUTED);
	}

	/** Opens a profile, returning here when it closes. */
	private void open(GameProfile profile) {
		// This screen, not a copy: coming back lands on the instance with its
		// list, its scroll and its loaded faces still in place.
		client.setScreen(new ProfileScreen(profile, this));
	}

	/** How tall one tier's row is, given how many lines its players need. */
	private static int rowHeight(DoorTierlist.Row row) {
		int lines = Math.max(1, (row.players().size() + PER_ROW - 1) / PER_ROW);
		return lines * FACE + (lines + 1) * GAP;
	}

	private void drawRow(DrawContext graphics, TextRenderer font, DoorTierlist.Row row,
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
		graphics.drawText(font, Text.literal(label),
				x + (LABEL_WIDTH - font.getWidth(label)) / 2,
				y + (height - font.fontHeight) / 2,
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
				hovered = player;
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
	private void drawFace(DrawContext graphics, DoorTierlist.Player player,
			int x, int y) {
		graphics.fill(x, y, x + FACE, y + FACE, GRID);

		Supplier<SkinTextures> lookup = skins.get(player.uuid());
		if (lookup == null) {
			requestSkin(player);
			return;
		}
		SkinTextures skin = lookup.get();
		if (skin == null) {
			return;
		}
		// A lookup serves the default skin until the real one arrives, and
		// keeps serving it if the texture never loads. Drawing it would put a
		// Steve head on someone who does not have one, so the cell is left
		// blank until the skin is actually theirs.
		//
		// A player who genuinely has the default skin matches this too, and
		// that is the intended answer: it is their skin, so it is drawn.
		if (isUnresolvedDefault(player.uuid(), skin)) {
			return;
		}
		graphics.drawTexture(RenderPipelines.GUI_TEXTURED, skin.body().texturePath(),
				x, y, 8.0f, 8.0f, FACE, FACE, 8, 8, 64, 64);
		graphics.drawTexture(RenderPipelines.GUI_TEXTURED, skin.body().texturePath(),
				x, y, 40.0f, 8.0f, FACE, FACE, 8, 8, 64, 64);
	}

	/**
	 * Whether this skin is the stand-in default rather than the player's own.
	 *
	 * <p>Asked only of a player we have no skin URL for. The skin itself cannot
	 * answer this: a lookup serves the default while the real texture loads and
	 * keeps serving it if the load fails, and the default a player would be
	 * given is identical to the default they may genuinely wear. The profile is
	 * what distinguishes them -- a player with a custom skin has a URL in their
	 * textures property, and one with the default has none.
	 */
	private boolean isUnresolvedDefault(UUID uuid, SkinTextures skin) {
		if (Boolean.TRUE.equals(wearsDefault.get(uuid))) {
			// Their skin really is the default, so this is it.
			return false;
		}
		SkinTextures fallback = DefaultSkinHelper.getSkinTextures(uuid);
		return skin.body().texturePath().equals(fallback.body().texturePath());
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
		requestSkin(player, false);
	}

	/**
	 * @param urgent true for a player someone is waiting on, which skips both
	 *     the in-flight cap and the backoff -- a click must not queue behind
	 *     faces nobody asked for, and with the cap full it otherwise could not
	 *     start at all
	 */
	private void requestSkin(DoorTierlist.Player player, boolean urgent) {
		UUID id = player.uuid();
		if (fetching.containsKey(id) || (!urgent && inFlight >= MAX_IN_FLIGHT)) {
			return;
		}
		Long waitUntil = retryAt.get(id);
		if (!urgent && waitUntil != null && System.currentTimeMillis() < waitUntil) {
			return;
		}
		int attempt = attempts.getOrDefault(id, 0);
		if (attempt >= MAX_ATTEMPTS) {
			return;
		}

		fetching.put(id, Boolean.TRUE);
		attempts.put(id, attempt + 1);
		inFlight++;

		// A thread of its own rather than the common pool: these calls block on
		// a socket, and the common pool is sized for work that does not.
		CompletableFuture
				.supplyAsync(() -> ClientCommands.texturedProfile(id, player.name()),
						SKIN_FETCHER)
				.thenAcceptAsync(profile -> {
					inFlight--;
					fetching.remove(id);
					if (profile == null) {
						// No lookup is registered, so the cell stays blank and
						// is tried again. Registering one here would draw a
						// default head, which must only ever mean the player
						// really has the default skin.
						int next = Math.min(attempt, RETRY_BACKOFF_MILLIS.length - 1);
						retryAt.put(id,
								System.currentTimeMillis() + RETRY_BACKOFF_MILLIS[next]);
						return;
					}
					// secureOnly false: the session server hands back unsigned
					// texture properties, and with true every looked-up player
					// renders as the default skin.
					profiles.put(id, profile);
					wearsDefault.put(id, !hasSkinUrl(profile));
					skins.put(id, client.getSkinProvider().supplySkinTextures(profile, false));
				}, client);
	}

	/**
	 * Whether this profile's textures actually name a skin.
	 *
	 * <p>The property is base64 JSON of the form
	 * {@code {"textures":{"SKIN":{"url":...}}}}. A player on the default skin
	 * has the property but no SKIN entry in it, which is how the two are told
	 * apart without resolving anything.
	 */
	private static boolean hasSkinUrl(GameProfile profile) {
		for (Property property : profile.properties().get("textures")) {
			try {
				String json = new String(Base64.getDecoder().decode(property.value()),
						StandardCharsets.UTF_8);
				JsonObject root = JsonParser.parseString(json).getAsJsonObject();
				JsonObject textures = root.getAsJsonObject("textures");
				if (textures != null && textures.has("SKIN")) {
					return true;
				}
			} catch (RuntimeException e) {
				// An unreadable property is treated as naming no skin, so the
				// player is drawn with the default rather than left blank.
				return false;
			}
		}
		return false;
	}

	/** The hovered player's name, beside the pointer. */
	private void drawNameTooltip(DrawContext graphics, String name,
			int mouseX, int mouseY) {
		TextRenderer font = this.textRenderer;
		int boxWidth = font.getWidth(name) + TOOLTIP_PADDING * 2;
		int boxHeight = font.fontHeight + TOOLTIP_PADDING * 2;
		int boxX = Tooltips.x(mouseX, boxWidth, width);
		int boxY = Math.clamp(mouseY - 8, 4, Math.max(4, height - boxHeight - 4));

		graphics.fill(boxX, boxY, boxX + boxWidth, boxY + boxHeight, 0xE00E1219);
		outline(graphics, boxX, boxY, boxX + boxWidth, boxY + boxHeight, GRID);
		graphics.drawTextWithShadow(font, Text.literal(name),
				boxX + TOOLTIP_PADDING, boxY + TOOLTIP_PADDING, 0xFFE4EAF2);
	}

	/**
	 * A one-pixel border, drawn inside the given bounds.
	 *
	 * <p>Four edges rather than a filled box, so whatever it marks stays
	 * visible underneath it.
	 */
	private static void outline(DrawContext graphics, int left, int top,
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

	/**
	 * Clicking a face opens that player's profile, the same screen
	 * {@code /tiers <player>} opens.
	 *
	 * <p>Opens only once the textured profile is in hand. The profile screen
	 * resolves its skin once, from the profile it is given, and never asks
	 * again -- so handing it a bare profile leaves that player as a default
	 * skin for as long as the screen is open. A click on a face whose textures
	 * have not arrived marks it wanted and opens as soon as they do.
	 */
	@Override
	public boolean mouseClicked(Click click, boolean doubleClick) {
		// The buttons are offered the click first. They sit outside the grid,
		// so the two cannot overlap today -- but `hovered` is left over from
		// the last frame drawn, and a widget should never lose a click to a
		// face the pointer is no longer on.
		if (super.mouseClicked(click, doubleClick)) {
			return true;
		}
		if (hovered != null) {
			GameProfile profile = profiles.get(hovered.uuid());
			if (profile != null) {
				open(profile);
			} else {
				// Not yet fetched, or fetched and failed. Remembered so the
				// draw opens it the moment the textures land, and the attempt
				// counter is cleared so a player who had given up is tried
				// again now that someone is waiting on them.
				pendingOpen = hovered;
				failedOpen = null;
				attempts.remove(hovered.uuid());
				retryAt.remove(hovered.uuid());
			}
			return true;
		}
		// super has already had its turn above, so this is not another call.
		return false;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
		scroll -= (int) (deltaY * 16);
		return true;
	}

	@Override
	public void close() {
		client.setScreen(parent);
	}

	/** The world keeps running behind it, as it does behind the profile screen. */
	@Override
	public boolean shouldPause() {
		return false;
	}
}
