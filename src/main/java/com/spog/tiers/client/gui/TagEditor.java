package com.spog.tiers.client.gui;

import com.spog.tiers.SpogTiersClient;
import com.spog.tiers.client.ClientCommands;
import com.spog.tiers.client.ModeIcons;
import com.spog.tiers.config.LayoutCode;
import com.spog.tiers.config.SpogTiersConfig;
import com.spog.tiers.config.TagLayout;
import com.spog.tiers.data.Gamemode;
import com.spog.tiers.data.PlayerGrade;
import com.spog.tiers.data.PlayerTiers;
import com.spog.tiers.data.Regions;
import com.spog.tiers.data.Tier;
import com.spog.tiers.data.TierList;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.text.MutableText;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The nametag tab: a live tag in the middle, settings either side.
 *
 * <p>Replaces three fixed rows of dropdowns with the tag itself. Everything
 * drawn in the centre is an element that can be clicked or dragged, and the
 * right panel then shows what that element can do -- so what a setting affects
 * is never in doubt, because the thing it affects is what was clicked.
 *
 * <p>Edits are made against a copy taken when the tab is opened. Save commits
 * it, Cancel throws it away, and Reset returns to the shipped layout without
 * committing anything -- so an editing session can always be abandoned whole.
 */
public final class TagEditor {
	/** How many steps back Undo can go. */
	private static final int HISTORY = 32;

	/**
	 * Where preview name lookups run.
	 *
	 * <p>One thread, and a daemon: the lookups are occasional and a queue of
	 * them is fine, but none should keep the game from closing.
	 */
	private static final java.util.concurrent.Executor LOOKUP =
			java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
				Thread thread = new Thread(runnable, "SpogTiers preview lookup");
				thread.setDaemon(true);
				return thread;
			});

	/** How tall the code box is, matching the switches beside it. */
	private static final int CODE_HEIGHT = 14;

	/**
	 * The gap between the card's border and the text inside it.
	 *
	 * <p>Without it the first character sat on the border, which no other
	 * field on this screen does.
	 */
	private static final int CODE_INSET = 4;

	private static final int PANEL_FILL = 0x50161B22;
	private static final int PANEL_BORDER = 0x70323B47;
	private static final int LABEL_COLOR = 0xFFB9C4D0;
	private static final int MUTED_COLOR = 0xFF6C7683;
	private static final int TEXT_COLOR = 0xFFD7DEE6;
	private static final int ACCENT = 0xFF6FC3E8;

	/** A switch reads as on or off by colour, not by position alone. */
	private static final int ON_FILL = 0xFF4CAF50;
	private static final int OFF_FILL = 0xFFC1443C;

	/**
	 * The colour a message that worked is written in.
	 *
	 * <p>The green a switch's text uses when it is on, so "it worked" reads
	 * the same here as it does everywhere else on this screen.
	 */
	private static final int GOOD_FILL = 0xFFA8E39B;


	/**
	 * A button's three shades, matching the general tab's ON and OFF.
	 *
	 * @param fill the panel behind the text
	 * @param hovered the same, lifted, while the pointer is on it
	 * @param border the edge, at mid brightness
	 * @param text the label, a light tint of the same hue
	 */
	private record Tint(int fill, int hovered, int border, int text) {
	}

	/**
	 * The panel a tooltip takes for a coloured button.
	 *
	 * <p>Drawn in the button's own hue, so the explanation is visibly tied to
	 * the control it came from rather than being one anonymous grey box.
	 */
	private record Explained(String text, Tint tint) {
	}

	// The hovered fill is a long way above the resting one on purpose: at the
	// general tab's own step the lift was barely visible on these small
	// buttons, so hovering did not read as anything happening.

	/** Green: commits, and adds. The general tab's own ON colours. */
	private static final Tint GREEN =
			new Tint(0x5023351F, 0xE04C8F3F, 0xA05F9A56, 0xFFA8E39B);

	/** Red: discards, and deletes. Its OFF colours. */
	private static final Tint RED =
			new Tint(0x50241A1D, 0xE0A6453D, 0xA0955A5A, 0xFFE0A0A0);

	/** Blue: steps back, which neither commits nor discards the session. */
	private static final Tint BLUE =
			new Tint(0x501B2A38, 0xE03E6E96, 0xA04E7EA8, 0xFF9CC9E8);

	/** Amber: starts over, which is neither of the two. */
	private static final Tint AMBER =
			new Tint(0x50332813, 0xE0B07B31, 0xA0A8813E, 0xFFE8C88A);

	private static final int ROW_HEIGHT = 22;
	private static final int PADDING = 10;

	/**
	 * How tall a row of the preview is.
	 *
	 * <p>Tighter than a control row: these are three lines of one tag, and
	 * spacing them like separate settings made the tag read as three unrelated
	 * things rather than one stacked over another.
	 */
	private static final int PREVIEW_ROW = 15;

	/** How wide the side panels are; the centre takes what is left. */
	private static final int SIDE_WIDTH = 168;

	/** The bar at the bottom of the right panel that delete is pinned to. */
	private static final int FOOTER_HEIGHT = 26;

	/** How far the pointer must travel before a click becomes a drag. */
	private static final int DRAG_SLOP = 3;

	/** The layout being edited, and the one to fall back to on cancel. */
	private TagLayout working;
	private TagLayout original;

	/**
	 * Every state the layout has been in this session, newest last.
	 *
	 * <p>Whole copies rather than a list of reversible actions: a layout is
	 * small, and a copy cannot disagree with what it is undoing the way a
	 * hand-written inverse can.
	 */
	private final Deque<TagLayout> history = new ArrayDeque<>();

	/** Who the preview is drawn for, and the box that name is typed into. */
	private UUID previewPlayer;
	private String previewName = "";
	private TextFieldWidget nameField;

	/** The box a shared code is pasted into. */
	private TextFieldWidget codeField;

	/** Where that box was drawn, for hit-testing and the text cursor. */
	private Button codeBox;

	/** Which element the right panel is showing, or null for none. */
	private TagLayout.Element selected;

	/** Set when a save is refused, and cleared by the next change. */
	private String complaint;

	/**
	 * Whether {@link #complaint} is good news rather than a refusal.
	 *
	 * <p>The same line carries both -- "Code copied!" and "That is not a
	 * layout code" appear in the same place -- so the colour is what tells
	 * them apart. Red for a refusal is the older meaning and stays the
	 * default; anything that worked has to say so.
	 */
	private boolean complaintGood;

	/** What to explain under the pointer this frame, or null. */
	private String hover;

	/** The same, for a coloured button, so its panel can match it. */
	private Explained hoverButton;

	private final TextRenderer font;

	/** Where each element was last drawn, for hit testing and dropping. */
	private final List<Hit> hits = new ArrayList<>();

	/** Where each button was last drawn, likewise. */
	private final List<Button> buttons = new ArrayList<>();

	/** Where each row was last drawn, so a drag can change rows. */
	private final List<RowBand> bands = new ArrayList<>();

	private final Dropdown<TagLayout.Kind> creator;
	private final Dropdown<Choice> tierList;
	private final Dropdown<Gamemode> tierMode;

	/** What to create next, and how far the right panel is scrolled. */
	private TagLayout.Kind creating = TagLayout.Kind.TIER;
	private int scroll;
	private int scrollMax;

	/** The element under the pointer while dragging, and where it started. */
	private TagLayout.Element dragging;
	private int dragFromX;
	private int dragFromY;
	private boolean dragMoved;

	private final Runnable onChange;

	/** Closes the screen, for Save & Close. */
	private Runnable closer = () -> { };

	public void onClose(Runnable closer) {
		this.closer = closer;
	}

	private record Hit(TagLayout.Element element, int left, int top, int right, int bottom) {
	}

	private record Button(String id, int left, int top, int right, int bottom) {
	}

	private record RowBand(TagLayout.Row row, int top, int bottom) {
	}

	/**
	 * What the list dropdown offers.
	 *
	 * <p>Its own type because null already means Best, so Door SMP needs a
	 * value of its own rather than another null.
	 */
	private record Choice(TierList list, boolean door) {
		static Choice best() {
			return new Choice(null, false);
		}

		static Choice of(TierList list) {
			return new Choice(list, false);
		}

		static Choice doorSmp() {
			return new Choice(null, true);
		}
	}

	/** The choice an element currently represents. */
	private static Choice current(TagLayout.Element element) {
		return element.doorSmp ? Choice.doorSmp() : Choice.of(element.list());
	}

	public TagEditor(TextRenderer font, Runnable onChange) {
		this.font = font;
		this.onChange = onChange;
		// Picking a kind clicks like every other control: the dropdown itself
		// is silent, and the screen only sounds the ones it routes.
		this.creator = new Dropdown<>(value -> {
			creating = value;
			click();
		});
		this.tierList = new Dropdown<>(value -> {
			click();
			if (selected != null) {
				selected.doorSmp = value.door();
				selected.list(value.list());
				// A mode the new list does not rank would resolve to nothing,
				// so it falls back to that list's best rather than being kept
				// as a setting that cannot work.
				if (selected.gamemode != null
						&& !modesFor(value.list()).contains(selected.gamemode)) {
					selected.gamemode = null;
				}
				changed();
			}
		});
		this.tierMode = new Dropdown<>(value -> {
			click();
			if (selected != null) {
				selected.gamemode = value;
				changed();
			}
		});
	}

	/** Vanilla's UI click, so the editor sounds like the rest of the game. */
	private static void click() {
		MinecraftClient.getInstance().getSoundManager().play(
				net.minecraft.client.sound.PositionedSoundInstance.ui(
						net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK, 1.0f));
	}

	/** Takes a fresh working copy. Call when the tab is opened. */
	public void open() {
		SpogTiersConfig config = SpogTiersClient.config();
		original = config.tagLayout.copy();
		working = config.tagLayout.copy();
		selected = null;
		complaint = null;
		dragging = null;
		scroll = 0;
		history.clear();
		// The player's own name to start with: the preview is most useful
		// showing the tag they will actually be wearing.
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player != null) {
			previewName = client.player.getGameProfile().name();
			previewPlayer = client.player.getUuid();
		}
	}

	/** Remembers the layout before a change, so Undo can put it back. */
	private void remember() {
		history.addLast(working.copy());
		while (history.size() > HISTORY) {
			history.removeFirst();
		}
	}

	/** Steps back one change. */
	private void undo() {
		if (history.isEmpty()) {
			return;
		}
		working = history.removeLast();
		// The selection is an object in the old list, which the restored copy
		// does not contain, so it cannot survive the step.
		selected = null;
		complaint = null;
	}

	/**
	 * Handles a key press. Delete removes the selected element, the same as
	 * the button does.
	 *
	 * @return true when the key was used here
	 */
	public boolean keyPressed(net.minecraft.client.input.KeyInput event) {
		TextFieldWidget field = nameField();
		if (field != null && field.isFocused()) {
			return field.keyPressed(event);
		}
		// The code box takes the keys before Delete is read as "remove the
		// selected element": someone editing a code expects Backspace to edit
		// it, not to take a piece off their tag.
		if (codeField != null && codeField.isFocused()) {
			return codeField.keyPressed(event);
		}
		// Delete and Backspace both remove: which one people reach for
		// depends on their keyboard, and neither means anything else here.
		if ((event.key() == 261 || event.key() == 259) && selected != null) {
			act("delete");
			return true;
		}
		return false;
	}

	/** Typing, when either box has focus. */
	public boolean charTyped(net.minecraft.client.input.CharInput event) {
		TextFieldWidget field = nameField();
		if (field != null && field.isFocused()) {
			return field.charTyped(event);
		}
		return codeField != null && codeField.isFocused() && codeField.charTyped(event);
	}

	/** Every dropdown, so the screen can route clicks and overlays to them. */
	public List<Dropdown<?>> dropdowns() {
		if (selected != null && selected.kind == TagLayout.Kind.TIER) {
			return List.of(creator, tierList, tierMode);
		}
		return List.of(creator);
	}

	public void closeDropdowns() {
		creator.close();
		tierList.close();
		tierMode.close();
	}

	/** Draws all three panels. */
	public void draw(DrawContext graphics, int left, int top, int right, int bottom,
			int mouseX, int mouseY) {
		if (working == null) {
			open();
		}
		hits.clear();
		buttons.clear();
		bands.clear();
		// Cleared with the rest: the code box is only where it was last
		// drawn, and on a tab that does not draw it a stale rectangle would
		// still take clicks.
		codeBox = null;
		hover = null;
		hoverButton = null;

		int centreLeft = left + SIDE_WIDTH + PADDING;
		int centreRight = right - SIDE_WIDTH - PADDING;

		drawGeneral(graphics, left, top, left + SIDE_WIDTH, bottom, mouseX, mouseY);
		drawPreview(graphics, centreLeft, top, centreRight, bottom, mouseX, mouseY);
		drawElement(graphics, centreRight + PADDING, top, right, bottom, mouseX, mouseY);

		// The dragged element rides the pointer, drawn last so it is over
		// everything it might be dropped onto.
		if (dragging != null && dragMoved) {
			int width = span(dragging);
			int x = mouseX - width / 2;
			int y = mouseY - PREVIEW_ROW / 2;
			graphics.fill(x, y, x + width, y + PREVIEW_ROW, 0x90222A35);
			outline(graphics, x, y, x + width, y + PREVIEW_ROW, ACCENT);
			drawElementText(graphics, dragging, x + 3,
					y + (PREVIEW_ROW - font.fontHeight) / 2, true);
		}
	}

	/**
	 * Shows the preview as a different player.
	 *
	 * <p>The tab list first, which is free and covers everyone on the server.
	 * Failing that the name goes to Mojang on a background thread, so any
	 * account can be previewed, not only the ones currently online. The typed
	 * name is shown either way; only the tiers wait on the answer.
	 *
	 * <p>Each lookup carries the name it was started for, and a late reply for
	 * a name that has since been retyped is dropped: without that, typing
	 * quickly could leave the preview showing whichever request happened to
	 * finish last.
	 */
	private void previewAs(String name) {
		previewName = name;
		previewPlayer = null;
		String wanted = name.trim();
		if (wanted.isEmpty()) {
			return;
		}

		MinecraftClient client = MinecraftClient.getInstance();
		if (client.getNetworkHandler() != null) {
			for (var entry : client.getNetworkHandler().getPlayerList()) {
				if (wanted.equalsIgnoreCase(entry.getProfile().name())) {
					adopt(wanted, entry.getProfile().id());
					return;
				}
			}
		}

		CompletableFuture
				.supplyAsync(() -> ClientCommands.resolveProfile(wanted), LOOKUP)
				.thenAcceptAsync(profile -> {
					if (profile != null) {
						adopt(wanted, profile.id());
					}
				}, client);
	}

	/** Takes a resolved id, unless the typed name has moved on since. */
	private void adopt(String forName, UUID id) {
		if (!forName.equalsIgnoreCase(previewName.trim())) {
			return;
		}
		previewPlayer = id;
		// Asked for while we are here, so the preview fills in rather than
		// staying on the stand-in tier.
		SpogTiersClient.service().requestNow(id);
	}


	/**
	 * The box a code is pasted into, built on first use and then moved.
	 *
	 * <p>Made here rather than by the screen because it belongs to this row
	 * and nothing else needs to know it exists. Rebuilt only when the width
	 * changes, so what someone has typed survives a resize.
	 */
	private void codeField(int x, int y, int width) {
		if (codeField == null || codeField.getWidth() != width) {
			String kept = codeField == null ? "" : codeField.getText();
			codeField = new TextFieldWidget(font, x, y, width, CODE_HEIGHT,
					Text.literal("Layout code"));
			// Vanilla's own box is a white outline on black, which is the one
			// thing on this screen that does not look like the rest of it.
			// Its border is turned off and the card behind it is drawn here
			// instead, in the same fill and border every panel uses.
			codeField.setDrawsBackground(false);
			codeField.setEditableColor(TEXT_COLOR);
			codeField.setPlaceholder(Text.literal("Paste a code"));
			// Long enough for any code this build writes, with room for codes
			// a later one might write.
			codeField.setMaxLength(2048);
			codeField.setText(kept);
		}
		codeField.setPosition(x, y);
	}

	/**
	 * Draws the card behind the code box, and the box on top of it.
	 *
	 * <p>The border lights to the accent while it has focus, which is how
	 * everything else on this screen shows what typing will reach.
	 */
	private void drawCodeField(DrawContext graphics, int x, int y, int width,
			int mouseX, int mouseY) {
		// An unbordered box draws its text at its own top-left with no inset
		// of its own -- vanilla only insets a bordered one -- so the box is
		// placed where the glyphs should land and the card is drawn around
		// it. Sizing the box to the card instead left the text against the
		// top edge.
		int textY = y + (CODE_HEIGHT - font.fontHeight) / 2;
		codeField(x + CODE_INSET, textY, width - CODE_INSET * 2);
		boolean focused = codeField.isFocused();
		graphics.fill(x, y, x + width, y + CODE_HEIGHT, PANEL_FILL);
		outline(graphics, x, y, x + width, y + CODE_HEIGHT,
				focused ? ACCENT : PANEL_BORDER);
		codeField.renderWidget(graphics, mouseX, mouseY, 0.0f);
		codeBox = new Button("code", x, y, x + width, y + CODE_HEIGHT);
	}

	/** The code box, so the screen can route typing to it. */
	public TextFieldWidget codeBox() {
		return codeField;
	}

	/** The name box, when one is on screen, so the screen can drive it. */
	public TextFieldWidget nameField() {
		return selected != null && selected.kind == TagLayout.Kind.NAME
				&& nameField != null && nameField.visible ? nameField : null;
	}

	/**
	 * Whether the pointer is over a box that takes typing.
	 *
	 * <p>Asked separately from {@link #isOverControl} because these two want
	 * different cursors: a button wants the hand, a text box wants the caret.
	 */
	public boolean isOverText(int mouseX, int mouseY) {
		if (codeBox != null && mouseX >= codeBox.left() && mouseX < codeBox.right()
				&& mouseY >= codeBox.top() && mouseY < codeBox.bottom()) {
			return true;
		}
		TextFieldWidget field = nameField();
		return field != null && inside(field, mouseX, mouseY);
	}

	/** Whether the pointer is over anything clickable, for the cursor. */
	public boolean isOverControl(int mouseX, int mouseY) {
		for (Button button : buttons) {
			if (mouseX >= button.left() && mouseX < button.right()
					&& mouseY >= button.top() && mouseY < button.bottom()) {
				return true;
			}
		}
		for (Hit hit : hits) {
			if (mouseX >= hit.left() && mouseX < hit.right()
					&& mouseY >= hit.top() && mouseY < hit.bottom()) {
				return true;
			}
		}
		return false;
	}

	/** What the pointer is over, for the screen to draw as a tooltip. */
	public String hoverText() {
		return hover;
	}

	/**
	 * Draws a coloured button's own tooltip, in that button's hue.
	 *
	 * <p>Drawn here rather than handed to the screen, because the screen's
	 * tooltip is one fixed grey and the point is that these are not.
	 */
	public void drawButtonTooltip(DrawContext graphics, int mouseX, int mouseY,
			int screenWidth, int screenHeight) {
		if (hoverButton == null) {
			return;
		}
		String text = hoverButton.text();
		Tint tint = hoverButton.tint();
		int boxWidth = font.getWidth(text) + 12;
		int boxHeight = font.fontHeight + 12;
		int boxX = Math.min(mouseX + 12, screenWidth - boxWidth - 4);
		int boxY = Math.clamp(mouseY - 8, 4, screenHeight - boxHeight - 4);

		graphics.fill(boxX, boxY, boxX + boxWidth, boxY + boxHeight, tint.border());
		graphics.fill(boxX + 1, boxY + 1, boxX + boxWidth - 1, boxY + boxHeight - 1,
				tint.fill() | 0xE0000000);
		graphics.drawTextWithShadow(font, Text.literal(text), boxX + 6, boxY + 6, tint.text());
	}

	/** Dropdown lists, drawn last so they sit over everything else. */
	public void drawOverlays(DrawContext graphics, int mouseX, int mouseY) {
		creator.drawOverlay(graphics, font, creating, mouseX, mouseY);
		if (selected != null && selected.kind == TagLayout.Kind.TIER) {
			tierList.drawOverlay(graphics, font, current(selected), mouseX, mouseY);
			tierMode.drawOverlay(graphics, font, selected.gamemode, mouseX, mouseY);
		}
	}

	// ---------------------------------------------------------------- panels

	private void drawGeneral(DrawContext graphics, int left, int top, int right,
			int bottom, int mouseX, int mouseY) {
		SpogTiersConfig config = SpogTiersClient.config();
		panel(graphics, left, top, right, bottom);
		int x = left + PADDING;
		int y = top + PADDING;

		graphics.drawTextWithShadow(font, Text.literal("Settings"), x, y, LABEL_COLOR);
		y += font.fontHeight + 6;
		y = rule(graphics, left, right, y);

		y = toggle(graphics, "Enabled", config.enabled, x, y, right - PADDING,
				mouseX, mouseY, "toggle.enabled");
		y = rule(graphics, left, right, y);

		graphics.drawTextWithShadow(font, Text.literal("Show tiers in..."), x, y, LABEL_COLOR);
		y += font.fontHeight + 6;
		y = toggle(graphics, "Nametag", config.showNametags, x, y, right - PADDING,
				mouseX, mouseY, "toggle.nametag");
		// Directly under Nametag, because it is the same setting for the tags
		// a server draws itself rather than a separate place tiers appear.
		y = toggle(graphics, "Custom Nametags", config.tagDisplays, x, y, right - PADDING,
				mouseX, mouseY, "toggle.displays");
		y = toggle(graphics, "Tab list", config.showTabList, x, y, right - PADDING,
				mouseX, mouseY, "toggle.tablist");
		y = toggle(graphics, "Chat", config.showInChat, x, y, right - PADDING,
				mouseX, mouseY, "toggle.chat");
		y = rule(graphics, left, right, y);

		y = toggle(graphics, "Center on name", working.centerOnName, x, y,
				right - PADDING, mouseX, mouseY, "toggle.centre");

		int duplicatesTop = y;
		toggle(graphics, "Prevent Duplicates", config.preventDuplicateTiers, x, y,
				right - PADDING, mouseX, mouseY, "toggle.duplicates");
		// Explained on hover: what it does depends on a Best option being
		// picked somewhere, which the label has no room to say.
		if (mouseX >= x && mouseX < right - PADDING
				&& mouseY >= duplicatesTop && mouseY < duplicatesTop + 16) {
			hover = "Prevent duplicate tiers from appearing when a best tierlist "
					+ "or best gamemode option is selected";
		}
	}

	/**
	 * The tag itself, as three rows of elements that can be dragged.
	 *
	 * <p>Drawn from the same layout the game draws from, so what is arranged
	 * here is what appears over a player's head.
	 */
	private void drawPreview(DrawContext graphics, int left, int top, int right,
			int bottom, int mouseX, int mouseY) {
		panel(graphics, left, top, right, bottom);

		int y = top + PADDING;
		graphics.drawTextWithShadow(font, Text.literal("Editor"), left + PADDING, y, LABEL_COLOR);
		y += font.fontHeight + 8;

		// A dark plate behind the rows, so the tag reads the way it does in
		// the world rather than as three lines of loose text.
		int plateTop = y;
		int plateHeight = PREVIEW_ROW * 3 + 8;
		graphics.fill(left + PADDING, plateTop, right - PADDING, plateTop + plateHeight,
				0xA0080C12);

		// How far the outer rows move to sit over the name, mirroring what
		// the game does, so the preview shows the arrangement that will be
		// drawn rather than one the editor invents.
		int shift = working.centerOnName ? nameShift(left + PADDING, right - PADDING) : 0;

		int rowY = plateTop + 4;
		for (TagLayout.Row row : TagLayout.Row.values()) {
			bands.add(new RowBand(row, rowY, rowY + PREVIEW_ROW));
			int nudge = row == TagLayout.Row.MIDDLE ? 0 : shift;
			drawRow(graphics, row, left + PADDING, rowY, right - PADDING, nudge,
					mouseX, mouseY);
			rowY += PREVIEW_ROW;
		}

		y = plateTop + plateHeight + 10;

		// What the code buttons had to say, on a line of its own above the
		// controls rather than beside the box that produced it. Down at the
		// foot of the pane it was the last thing on a crowded edge and went
		// unread; here it has the full width of the column. It is drawn
		// before the row below is placed so that row moves down as a whole,
		// keeping the creator and the buttons on the same line as each
		// other, and only when there is something to say.
		if (complaint != null) {
			graphics.drawTextWithShadow(font, Text.literal(complaint), left + PADDING, y,
					complaintGood ? GOOD_FILL : OFF_FILL);
			y += font.fontHeight + 4;
		}

		// Pick a kind and press plus; it lands at the end of the middle row
		// where it can be seen and then dragged.
		// A second name would draw the player's name twice with no way to
		// tell the two apart, so it is not offered once one exists.
		List<Dropdown.Entry<TagLayout.Kind>> kinds = new ArrayList<>();
		for (TagLayout.Kind kind : TagLayout.Kind.values()) {
			if (kind == TagLayout.Kind.NAME && working.hasName()) {
				continue;
			}
			kinds.add(new Dropdown.Entry<>(kind, kind.title(), null));
		}
		if (creating == TagLayout.Kind.NAME && working.hasName()) {
			creating = TagLayout.Kind.TIER;
		}
		int addSize = 18;
		int listWidth = Math.min(140, right - PADDING - addSize - 6 - (left + PADDING));
		creator.setEntries(kinds);
		creator.setBounds(left + PADDING, y, listWidth);
		creator.draw(graphics, font, creating, mouseX, mouseY);
		int addX = left + PADDING + listWidth + 6;
		plus(graphics, addX, y, addSize, mouseX, mouseY);

		// The session's controls sit on this row too, to the right of the
		// plus: they belong with the editing, not down beside Done, which
		// only closes the screen.
		// Beside the plus while they fit there, which they do at any ordinary
		// window size. At a large GUI scale this column is only a few dozen
		// pixels wide, and the three of them ran off its right-hand edge, so
		// they wrap onto rows of their own -- as many to a row as there is
		// room for.
		int narrow = 56;
		int gap = 6;
		int barX = addX + addSize + gap * 2;
		String[] labels = {"Undo", "Reset", "Revert"};
		String[] ids = {"undo", "reset", "revert"};
		Tint[] tints = {BLUE, AMBER, RED};
		String[] explains = {
			"Rolls back the most recent change",
			"Resets the tag to default settings",
			"Rolls back all changes done in this editing session",
		};
		int perRow = fitPerRow(right - PADDING - barX, narrow, gap, labels.length);
		if (perRow < labels.length) {
			// Below the creator rather than beside it, where the whole width
			// of the column is theirs instead of what the plus left over.
			barX = left + PADDING;
			y += ROW_HEIGHT + 4;
			perRow = fitPerRow(right - PADDING - barX, narrow, gap, labels.length);
		}

		for (int i = 0; i < labels.length; i++) {
			colouredButton(graphics, labels[i], barX + i % perRow * (narrow + gap),
					y + i / perRow * (ROW_HEIGHT + 4), narrow, mouseX, mouseY, ids[i],
					tints[i], explains[i]);
		}
		y += (labels.length - 1) / perRow * (ROW_HEIGHT + 4);
		y += ROW_HEIGHT + 6;

		// Sharing is pinned to the foot of the pane rather than following the
		// buttons down it: it is the one thing here that is not editing, and
		// the rows above it change height as elements are added, which kept
		// moving it. At the bottom it is always in the same place.
		// Except when the buttons above have wrapped far enough down to reach
		// it -- then it follows them instead of being drawn over them, which
		// is what a fixed position would do at a large GUI scale.
		drawCodeRow(graphics, left, Math.max(y, bottom - PADDING - ROW_HEIGHT), right,
				mouseX, mouseY);

		// Save, Cancel and Reset are drawn where Done sits, by drawActions.
	}

	/**
	 * The sharing row: a box to paste a code into, and the three buttons that
	 * work on it.
	 *
	 * <p>Clear, Import, Export, left to right. Clear is beside the box it
	 * empties, and Import next to it because that is the order the job runs
	 * in -- paste, then load. Export is last because it writes rather than
	 * reads, and putting it under the pointer that just pressed Import would
	 * overwrite the code someone had only just pasted.
	 */
	private void drawCodeRow(DrawContext graphics, int left, int y, int right,
			int mouseX, int mouseY) {
		int codeGap = 6;
		// Narrower than the row above: three buttons and a box share this
		// width where that row shares it between three buttons alone.
		int codeButton = 44;
		int span = right - PADDING - (left + PADDING);
		int boxWidth = Math.max(40, span - (codeButton + codeGap) * 3);
		int boxY = y + (ROW_HEIGHT - CODE_HEIGHT) / 2;
		drawCodeField(graphics, left + PADDING, boxY, boxWidth, mouseX, mouseY);

		int x = left + PADDING + boxWidth + codeGap;
		colouredButton(graphics, "Clear", x, y, codeButton, mouseX, mouseY,
				"clear", AMBER, "Empties the code box");
		x += codeButton + codeGap;
		colouredButton(graphics, "Import", x, y, codeButton, mouseX, mouseY,
				"import", GREEN, "Loads the tag from the code in the box");
		x += codeButton + codeGap;
		colouredButton(graphics, "Export", x, y, codeButton, mouseX, mouseY,
				"export", BLUE,
				"Puts a code for this tag in the box, and on the clipboard");
	}

	/** One row of the preview, and the hit boxes for its elements. */
	private void drawRow(DrawContext graphics, TagLayout.Row row, int left, int y,
			int right, int shift, int mouseX, int mouseY) {
		List<TagLayout.Element> elements = new ArrayList<>(working.row(row));
		// The element being dragged leaves the row while it is in the air, so
		// the rest close up and the caret sits on a real seam. Leaving its gap
		// behind made the caret land inside a neighbour -- between a tier's
		// icon and its label, most visibly.
		if (dragging != null && dragMoved) {
			elements.remove(dragging);
		}
		int textY = y + (PREVIEW_ROW - font.fontHeight) / 2;

		boolean over = dragging != null && dragMoved
				&& mouseY >= y && mouseY < y + PREVIEW_ROW
				&& mouseX >= left && mouseX < right;

		if (elements.isEmpty()) {
			return;
		}

		// Centred, the way every row of a nameplate is drawn in the world.
		// Measured with the same function that draws, so an icon is counted
		// in: measuring the plain text while drawing an icon too pushed each
		// row right by however wide its icons were, and rows with different
		// icons ended up misaligned with each other.
		int width = 0;
		for (TagLayout.Element element : elements) {
			width += span(element);
		}
		int x = left + Math.max(4, ((right - left) - width) / 2) + shift;

		for (TagLayout.Element element : elements) {
			int span = span(element);
			boolean hovered = mouseX >= x && mouseX < x + span
					&& mouseY >= y && mouseY < y + PREVIEW_ROW;
			boolean chosen = element == selected;

			// Hover still needs a hint of a box to show it is grabbable, but
			// the selection itself is carried by the text, not a panel behind it.
			if (hovered && dragging == null) {
				graphics.fill(x, y + 1, x + span, y + PREVIEW_ROW - 1, 0x18FFFFFF);
			}
			drawElementText(graphics, element, x + 3, textY, chosen);

			hits.add(new Hit(element, x, y, x + span, y + PREVIEW_ROW));
			x += span;
		}

		// A caret exactly where the element would land, rather than lighting
		// the whole row: the row says which line it goes on, the caret says
		// where along it, which is the part a drop actually decides.
		if (over) {
			caret(graphics, dropX(row, left, right, mouseX), y);
		}
	}

	/**
	 * How far the outer rows move to line up on the name, in pixels.
	 *
	 * <p>The middle row is centred on its whole width, so the name's own
	 * middle sits off that centre by half of the difference between what
	 * precedes it and what follows. Moving the other rows by the same amount
	 * puts all three over the name.
	 */
	private int nameShift(int left, int right) {
		List<TagLayout.Element> middle = working.row(TagLayout.Row.MIDDLE);
		int before = 0;
		int after = 0;
		boolean seen = false;
		for (TagLayout.Element element : middle) {
			if (element.kind == TagLayout.Kind.NAME) {
				seen = true;
				continue;
			}
			if (seen) {
				after += span(element);
			} else {
				before += span(element);
			}
		}
		return (before - after) / 2;
	}

	/**
	 * Where along a row a drop at {@code mouseX} would land, in pixels.
	 *
	 * <p>Worked from the same centring the row is drawn with, so the caret is
	 * on the seam the element will actually be inserted at.
	 */
	private int dropX(TagLayout.Row row, int left, int right, int mouseX) {
		List<TagLayout.Element> elements = new ArrayList<>(working.row(row));
		elements.remove(dragging);
		if (elements.isEmpty()) {
			return left + Math.max(4, (right - left) / 2);
		}

		int width = 0;
		for (TagLayout.Element element : elements) {
			width += span(element);
		}
		int x = left + Math.max(4, ((right - left) - width) / 2);
		// Seams only: the caret lands before or after a whole element, never
		// part way through one, so it cannot appear between a tier's icon and
		// its label.
		for (TagLayout.Element element : elements) {
			int span = span(element);
			if (mouseX < x + span / 2) {
				return x;
			}
			x += span;
		}
		return x;
	}

	/** The insertion mark shown while dragging. */
	private void caret(DrawContext graphics, int x, int y) {
		graphics.fill(x - 1, y + 1, x + 1, y + PREVIEW_ROW - 1, ACCENT);
		graphics.fill(x - 3, y + 1, x + 3, y + 2, ACCENT);
		graphics.fill(x - 3, y + PREVIEW_ROW - 2, x + 3, y + PREVIEW_ROW - 1, ACCENT);
	}

	/**
	 * One element's text, in the colours it will actually be drawn in.
	 *
	 * <p>A tier shows its list's icon and its own colour, so the preview says
	 * what the tag will look like rather than only what is in it. The
	 * selected element is drawn highlighted rather than boxed.
	 */
	private void drawElementText(DrawContext graphics, TagLayout.Element element,
			int x, int y, boolean chosen) {
		if (element.kind == TagLayout.Kind.TIER) {
			Text icon = SpogTiersClient.config().showTagIcons ? icon(element) : null;
			Tier sample = tierFor(element);
			int cursor = x;
			if (icon != null) {
				// Drawn white and on its own: the glyphs are coloured artwork,
				// and passing the tier colour through tinted them to it, so
				// every list's icon came out the same shade as the label.
				graphics.drawTextWithShadow(font, icon, cursor, y, 0xFFFFFFFF);
				cursor += font.getWidth(icon) + font.getWidth(" ");
			}
			String label = labelFor(element, sample);
			graphics.drawTextWithShadow(font, Text.literal(label), cursor, y,
					chosen ? 0xFFFFFFFF : sample.color());
			if (chosen) {
				underline(graphics, x, y, (cursor - x) + font.getWidth(label));
			}
			return;
		}
		String plain = preview(element);
		graphics.drawTextWithShadow(font, Text.literal(plain), x, y,
				chosen ? 0xFFFFFFFF : colour(element));
		if (chosen) {
			underline(graphics, x, y, font.getWidth(plain));
		}
	}

	/** The mark under a selected element, in place of a box around it. */
	private void underline(DrawContext graphics, int x, int y, int width) {
		graphics.fill(x, y + font.fontHeight, x + width, y + font.fontHeight + 1, ACCENT);
	}

	/**
	 * The right panel: what the selected element can do.
	 *
	 * <p>Scrolls, because a tier element carries two dropdowns and a
	 * separator a palette, which is more than fits on a short screen.
	 */
	private void drawElement(DrawContext graphics, int left, int top, int right,
			int bottom, int mouseX, int mouseY) {
		panel(graphics, left, top, right, bottom);
		int x = left + PADDING;
		int footer = bottom - FOOTER_HEIGHT;

		if (selected == null) {
			// Centred both ways: with nothing to show, text tucked into the
			// top corner reads as a panel that failed to draw rather than one
			// that is deliberately empty.
			String empty = "Nothing selected";
			graphics.drawTextWithShadow(font, Text.literal(empty),
					left + (right - left - font.getWidth(empty)) / 2,
					top + (bottom - top - font.fontHeight) / 2, MUTED_COLOR);
			scrollMax = 0;
			return;
		}

		int y = top + PADDING - scroll;
		graphics.drawTextWithShadow(font, Text.literal(selected.title()), x, y, LABEL_COLOR);
		y += font.fontHeight + 8;

		if (selected.kind == TagLayout.Kind.TIER) {
			graphics.drawTextWithShadow(font, Text.literal("List"), x, y, MUTED_COLOR);
			y += font.fontHeight + 4;
			List<Dropdown.Entry<Choice>> lists = new ArrayList<>();
			lists.add(new Dropdown.Entry<>(Choice.best(), "Best", null));
			for (TierList list : TierList.values()) {
				lists.add(new Dropdown.Entry<>(Choice.of(list), list.displayName(),
						ConfigScreen.logoOf(list)));
			}
			lists.add(new Dropdown.Entry<>(Choice.doorSmp(), "Door SMP", null));
			tierList.setEntries(lists);
			tierList.setBounds(x, y, right - x - PADDING);
			tierList.draw(graphics, font, current(selected), mouseX, mouseY);
			y += ROW_HEIGHT + 8;

			// Our own list has one grade per player and no modes to choose
			// between, so the second dropdown would only offer nothing.
			if (selected.doorSmp) {
				scrollMax = Math.max(0, (y + scroll) - (bottom - FOOTER_HEIGHT) + PADDING);
				graphics.fill(left + 1, bottom - FOOTER_HEIGHT, right - 1,
						bottom - FOOTER_HEIGHT + 1, PANEL_BORDER);
				graphics.fill(left + 1, bottom - FOOTER_HEIGHT + 1, right - 1, bottom - 1,
						0x40101720);
				int doorInset = (FOOTER_HEIGHT - 18) / 2;
				trash(graphics, right - doorInset - 18, bottom - FOOTER_HEIGHT + doorInset,
						18, mouseX, mouseY);
				return;
			}

			graphics.drawTextWithShadow(font, Text.literal("Gamemode"), x, y, MUTED_COLOR);
			y += font.fontHeight + 4;
			// Only the modes the chosen list actually ranks. Offering all of
			// them let someone pick a mode their list has never heard of,
			// which then resolved to nothing and looked like a broken tag.
			// A Best element has no one list, so it offers every mode any
			// enabled list ranks.
			List<Dropdown.Entry<Gamemode>> modes = new ArrayList<>();
			modes.add(new Dropdown.Entry<>(null, "Best", null));
			for (Gamemode mode : modesFor(selected.list())) {
				// A Best element has no one list to take artwork from, so its
				// modes are drawn from whichever list ranks them.
				TierList owner = selected.list() != null ? selected.list() : ownerOf(mode);
				modes.add(new Dropdown.Entry<>(mode, mode.displayName(),
						ConfigScreen.modeIcon(owner, mode)));
			}
			tierMode.setEntries(modes);
			tierMode.setBounds(x, y, right - x - PADDING);
			tierMode.draw(graphics, font, selected.gamemode, mouseX, mouseY);
			y += ROW_HEIGHT + 8;
		}

		if (selected.kind == TagLayout.Kind.SEPARATOR) {
			graphics.drawTextWithShadow(font, Text.literal("Character"), x, y, MUTED_COLOR);
			y += font.fontHeight + 4;
			// A row of the characters worth having: typing one in would need a
			// text field, and these are what a separator is ever set to.
			String[] choices = {"|", "/", "-", "·", "•", ":"};
			int cx = x;
			for (String choice : choices) {
				boolean picked = choice.equals(selected.character);
				pill(graphics, choice, cx, y, 20, mouseX, mouseY, "char." + choice, picked);
				cx += 22;
				if (cx + 20 > right - PADDING) {
					cx = x;
					y += ROW_HEIGHT;
				}
			}
			y += ROW_HEIGHT + 8;

			graphics.drawTextWithShadow(font, Text.literal("Color"), x, y, MUTED_COLOR);
			y += font.fontHeight + 4;
			int[] palette = {0x555555, 0xFFFFFF, 0xB9C4D0, 0x6FC3E8, 0x89F19C,
					0xEDE04E, 0xEB8526, 0xD46A6A, 0xA034C7};
			int sx = x;
			for (int colour : palette) {
				graphics.fill(sx, y, sx + 18, y + 18, 0xFF000000 | colour);
				if (colour == selected.colour) {
					outline(graphics, sx - 1, y - 1, sx + 19, y + 19, ACCENT);
				}
				buttons.add(new Button("colour." + colour, sx, y, sx + 18, y + 18));
				sx += 20;
				if (sx + 18 > right - PADDING) {
					sx = x;
					y += 20;
				}
			}
			y += 28;
		}

		// How far the content ran past the footer, so scrolling knows its limit.
		scrollMax = Math.max(0, (y + scroll) - footer + PADDING);

		if (nameField != null && nameField.visible) {
			nameField.renderWidget(graphics, mouseX, mouseY, 0.0f);
		}

		// The footer, and delete pinned to its right. Drawn after the content
		// so a long panel scrolls underneath rather than over it.
		graphics.fill(left + 1, footer, right - 1, footer + 1, PANEL_BORDER);
		graphics.fill(left + 1, footer + 1, right - 1, bottom - 1, 0x40101720);
		// The same gap from the right edge as from the top and bottom of the
		// footer, so it sits square in its corner.
		int inset = (FOOTER_HEIGHT - 18) / 2;
		trash(graphics, right - inset - 18, footer + inset, 18, mouseX, mouseY);
	}

	// ---------------------------------------------------------------- input

	/** @return true when the click was handled here */
	public boolean click(net.minecraft.client.gui.Click event, boolean doubled) {
		// Focus follows the click: inside the box it takes typing, outside it
		// gives it back so Delete removes an element again.
		TextFieldWidget field = nameField();
		if (field != null) {
			boolean inside = inside(field, event.x(), event.y());
			field.setFocused(inside);
			if (inside) {
				// Handed the click itself, not just the focus: that is what
				// puts the caret where it was clicked and starts a
				// selection, so text can be dragged over and a double click
				// takes a word.
				field.mouseClicked(event, doubled);
				return true;
			}
		}
		if (codeField != null) {
			// The card's border, not the box inside it: the inset between
			// the two is part of the field as far as clicking goes, and
			// missing by two pixels reads as the box being broken.
			boolean inside = codeBox != null
					&& event.x() >= codeBox.left() && event.x() < codeBox.right()
					&& event.y() >= codeBox.top() && event.y() < codeBox.bottom();
			codeField.setFocused(inside);
			if (inside) {
				codeField.mouseClicked(event, doubled);
				return true;
			}
		}
		double mouseX = event.x();
		double mouseY = event.y();
		for (Button button : buttons) {
			if (mouseX >= button.left() && mouseX < button.right()
					&& mouseY >= button.top() && mouseY < button.bottom()) {
				return act(button.id());
			}
		}
		for (Hit hit : hits) {
			if (mouseX >= hit.left() && mouseX < hit.right()
					&& mouseY >= hit.top() && mouseY < hit.bottom()) {
				selected = hit.element();
				scroll = 0;
				closeDropdowns();
				// Held rather than moved: a drag only begins once the pointer
				// has travelled, so a plain click still just selects.
				dragging = hit.element();
				dragFromX = (int) mouseX;
				dragFromY = (int) mouseY;
				dragMoved = false;
				return true;
			}
		}
		return false;
	}

	/** Whether a point is within a box's bounds. */
	private static boolean inside(TextFieldWidget box, double x, double y) {
		return x >= box.getX() && x < box.getX() + box.getWidth()
				&& y >= box.getY() && y < box.getY() + box.getHeight();
	}

	/**
	 * Tracks a held element, or extends a selection in a focused box.
	 *
	 * <p>Called while the button is down.
	 */
	public void drag(net.minecraft.client.gui.Click event,
			double dragX, double dragY) {
		// A box with focus owns the drag: it is selecting text, and nothing
		// on the tag is being moved.
		TextFieldWidget field = nameField();
		if (field != null && field.isFocused()) {
			field.mouseDragged(event, dragX, dragY);
			return;
		}
		if (codeField != null && codeField.isFocused()) {
			codeField.mouseDragged(event, dragX, dragY);
			return;
		}
		drag(event.x(), event.y());
	}

	/** Tracks a held element. Called while the button is down. */
	public void drag(double mouseX, double mouseY) {
		if (dragging == null) {
			return;
		}
		if (!dragMoved && (Math.abs(mouseX - dragFromX) > DRAG_SLOP
				|| Math.abs(mouseY - dragFromY) > DRAG_SLOP)) {
			dragMoved = true;
		}
	}

	/**
	 * Drops a dragged element wherever the pointer let go.
	 *
	 * @return true when a drop was handled here
	 */
	public boolean release(double mouseX, double mouseY) {
		if (dragging == null) {
			return false;
		}
		TagLayout.Element moved = dragging;
		boolean wasDrag = dragMoved;
		dragging = null;
		dragMoved = false;
		if (!wasDrag) {
			return false;
		}

		TagLayout.Row target = null;
		for (RowBand band : bands) {
			if (mouseY >= band.top() && mouseY < band.bottom()) {
				target = band.row();
				break;
			}
		}
		if (target == null) {
			return true;
		}

		// Placed by where it was dropped along the row rather than appended,
		// counted the same way the caret is positioned so the element lands
		// where the mark said it would.
		int at = 0;
		for (TagLayout.Element element : working.row(target)) {
			if (element == moved) {
				continue;
			}
			Hit hit = hitFor(element);
			if (hit != null && mouseX >= (hit.left() + hit.right()) / 2.0) {
				at++;
			}
		}

		working.elements.remove(moved);
		moved.row = target;
		insertAt(moved, target, at);
		changed();
		return true;
	}

	/** Puts an element at a place along its row, in the backing list. */
	private void insertAt(TagLayout.Element element, TagLayout.Row row, int at) {
		List<TagLayout.Element> existing = working.row(row);
		if (at >= existing.size()) {
			working.elements.add(element);
			return;
		}
		int index = working.elements.indexOf(existing.get(at));
		working.elements.add(index, element);
	}

	private Hit hitFor(TagLayout.Element element) {
		for (Hit hit : hits) {
			if (hit.element() == element) {
				return hit;
			}
		}
		return null;
	}

	public boolean scroll(double amount) {
		if (scrollMax <= 0) {
			return false;
		}
		scroll = Math.clamp(scroll - (int) (amount * 12), 0, scrollMax);
		return true;
	}

	/** Runs whatever a button id means. */
	private boolean act(String id) {
		SpogTiersConfig config = SpogTiersClient.config();
		switch (id) {
			case "create" -> {
				remember();
				TagLayout.Element element =
						new TagLayout.Element(creating, TagLayout.Row.MIDDLE);
				working.elements.add(element);
				selected = element;
				changed();
			}
			case "delete" -> {
				if (selected != null) {
					remember();
					working.elements.remove(selected);
					selected = null;
					changed();
				}
			}
			case "done" -> closer.run();
			case "undo" -> {
				undo();
				changed();
			}
			case "revert" -> {
				// Back to how the tag was when the tab was opened, saved as
				// well: with edits committing immediately, undoing them has to
				// commit too or the file keeps the abandoned arrangement.
				remember();
				working = original.copy();
				selected = null;
				changed();
			}
			case "reset" -> {
				remember();
				working = TagLayout.defaults();
				selected = null;
				changed();
			}
			case "export" -> share();
			case "import" -> paste();
			case "clear" -> clear();
			case "toggle.enabled" -> {
				config.enabled = !config.enabled;
				config.save();
			}
			case "toggle.nametag" -> {
				config.showNametags = !config.showNametags;
				config.save();
			}
			case "toggle.tablist" -> {
				config.showTabList = !config.showTabList;
				config.save();
			}
			case "toggle.chat" -> {
				config.showInChat = !config.showInChat;
				config.save();
			}
			case "toggle.displays" -> {
				config.tagDisplays = !config.tagDisplays;
				config.save();
			}
			case "toggle.centre" -> {
				remember();
				working.centerOnName = !working.centerOnName;
				changed();
			}
			case "toggle.duplicates" -> {
				config.preventDuplicateTiers = !config.preventDuplicateTiers;
				config.save();
			}
			default -> {
				if (id.startsWith("char.") && selected != null) {
					selected.character = id.substring("char.".length());
					changed();
				} else if (id.startsWith("colour.") && selected != null) {
					selected.colour = Integer.parseInt(id.substring("colour.".length()));
					changed();
				} else {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * Commits the layout as it stands and tells the caller it moved.
	 *
	 * <p>Every edit saves at once, so the tag in the world matches the editor
	 * without anyone pressing anything. Revert is what puts it back, from the
	 * copy taken when the tab was opened.
	 */
	/**
	 * Empties the code box.
	 *
	 * <p>Its own button because clearing a couple of hundred characters by
	 * hand is a held Backspace, and because the box falls back to the
	 * clipboard when it is empty -- so emptying it is also how you tell
	 * Import to use what you have just copied.
	 */
	private void clear() {
		if (codeField != null) {
			codeField.setText("");
			codeField.setFocused(true);
		}
		complaint = null;
	}

	/** Says something went well, in green. */
	private void report(String message) {
		complaint = message;
		complaintGood = true;
	}

	/** Says something was refused, in red. */
	private void refuse(String message) {
		complaint = message;
		complaintGood = false;
	}

	/**
	 * Puts a code for this tag and its settings on the clipboard.
	 *
	 * <p>The clipboard rather than a file or a screen full of text: a code is
	 * only useful once it is somewhere it can be sent to someone, and every
	 * way of sending it starts with a paste.
	 */
	private void share() {
		String code = LayoutCode.write(SpogTiersClient.config());
		if (code == null) {
			refuse("Could not build a code for this tag");
			return;
		}
		// Into the box as well as the clipboard: the box is where someone
		// looks to see that it worked, and having it there means it can be
		// re-copied by hand if the clipboard call went nowhere.
		if (codeField != null) {
			codeField.setText(code);
		}
		MinecraftClient.getInstance().keyboard.setClipboard(code);
		report("Code copied!");
	}

	/**
	 * Loads a tag from a code on the clipboard.
	 *
	 * <p>Read from the clipboard rather than typed into a box: the codes run
	 * to a couple of hundred characters, which nobody is going to retype, and
	 * anyone who has been sent one already has it copied.
	 *
	 * <p>The current tag is remembered first, so an import that turns out to
	 * be someone else's taste is one Undo away rather than a loss.
	 */
	private void paste() {
		// What is in the box, falling back to the clipboard when it is empty:
		// someone who has just copied a code can press Import without pasting
		// it first, and someone who typed one in gets theirs.
		String typed = codeField == null ? "" : codeField.getText().trim();
		String code = typed.isEmpty()
				? MinecraftClient.getInstance().keyboard.getClipboard() : typed;
		LayoutCode.Result result = LayoutCode.read(code);
		if (!result.ok()) {
			refuse(result.problem().message());
			return;
		}
		remember();
		SpogTiersConfig config = SpogTiersClient.config();
		String note = LayoutCode.apply(result.parts(), config);
		// Re-read from the config rather than trusting what went in: apply
		// normalises, so what is drawn has to be what was actually kept.
		working = config.tagLayout.copy();
		original = working.copy();
		selected = null;
		if (onChange != null) {
			onChange.run();
		}
		report(note == null ? "Tag imported" : note);
	}

	private void changed() {
		complaint = null;
		SpogTiersConfig config = SpogTiersClient.config();
		config.tagLayout = working.copy();
		config.save();
		if (onChange != null) {
			onChange.run();
		}
	}

	// ---------------------------------------------------------------- pieces

	/**
	 * How wide an element is drawn, its icon included.
	 *
	 * <p>The one measurement every part of the row uses -- centring, hit
	 * boxes and the dragged ghost -- so what is measured is always what is
	 * drawn.
	 */
	private int span(TagLayout.Element element) {
		if (element.kind == TagLayout.Kind.TIER) {
			Text icon = SpogTiersClient.config().showTagIcons ? icon(element) : null;
			int width = font.getWidth(labelFor(element, tierFor(element)));
			if (icon != null) {
				width += font.getWidth(icon) + font.getWidth(" ");
			}
			return width + 6;
		}
		return font.getWidth(preview(element)) + 6;
	}

	/** The text a tier element draws: our own grade, or the list's tier. */
	private String labelFor(TagLayout.Element element, Tier tier) {
		if (element.doorSmp) {
			PlayerGrade grade = gradeForPreview();
			return grade != null && grade.isGraded() ? grade.label() : "S";
		}
		return tier.label();
	}

	/** The colour a tier element draws in. */
	private int colourFor(TagLayout.Element element) {
		if (element.doorSmp) {
			PlayerGrade grade = gradeForPreview();
			return grade != null && grade.isGraded()
					? grade.foreground() : 0xFFFFFFFF;
		}
		return tierFor(element).color();
	}

	/** What an element reads as in the preview. */
	private String preview(TagLayout.Element element) {
		return switch (element.kind) {
			case NAME -> previewName.isBlank() ? "Player" : previewName;
			case REGION -> region();
			case SEPARATOR -> element.character;
			case TIER -> tierPreview(element);
		};
	}

	/** A tier's preview text, icon included, for measuring its width. */
	private String tierPreview(TagLayout.Element element) {
		Text icon = icon(element);
		String label = labelFor(element, tierFor(element));
		return icon != null && SpogTiersClient.config().showTagIcons
				? icon.getString() + " " + label : label;
	}

	/**
	 * The gamemodes a tier element may be set to.
	 *
	 * <p>One list ranks only its own; a Best element is not tied to a list, so
	 * it takes the union of every list's, which is what it will search.
	 */
	private List<Gamemode> modesFor(TierList list) {
		if (list != null) {
			return list.gamemodes();
		}
		List<Gamemode> all = new ArrayList<>();
		for (TierList each : TierList.values()) {
			for (Gamemode mode : each.gamemodes()) {
				if (!all.contains(mode)) {
					all.add(mode);
				}
			}
		}
		return all;
	}

	/** Any list that ranks this mode, for a Best element's artwork. */
	private TierList ownerOf(Gamemode mode) {
		for (TierList list : TierList.values()) {
			if (list.gamemodes().contains(mode)) {
				return list;
			}
		}
		return null;
	}

	/** The icon a tier element would draw, or null when it has none. */
	private Text icon(TagLayout.Element element) {
		if (element.doorSmp) {
			return ModeIcons.doorRaised();
		}
		TierList list = listFor(element);
		if (list == null) {
			return null;
		}
		Gamemode mode = element.gamemode;
		if (mode == null) {
			// The mode the tier on show actually came from, not the first the
			// list happens to declare: a Best element drawing an HT4 mace was
			// showing the axe icon because axe sorts first.
			mode = modeOf(list, element);
		}
		return mode == null ? null : ModeIcons.of(list, mode.key());
	}

	/**
	 * Which list an element draws from.
	 *
	 * <p>A Best element has no list of its own, so it is the one holding the
	 * tier the preview is showing; before anything is cached there is nothing
	 * to go on, and the icon is left off rather than guessed at.
	 */
	private TierList listFor(TagLayout.Element element) {
		if (element.list() != null) {
			return element.list();
		}
		if (previewPlayer == null) {
			return null;
		}
		Tier shown = tierFor(element);
		for (var entry : SpogTiersClient.cache().allLists(previewPlayer).entrySet()) {
			PlayerTiers tiers = entry.getValue();
			if (tiers == null) {
				continue;
			}
			Tier best = tiers.best();
			if (best != null && best.equals(shown)) {
				return entry.getKey();
			}
		}
		return null;
	}

	/** The gamemode the shown tier came from, or null when it is not known. */
	private Gamemode modeOf(TierList list, TagLayout.Element element) {
		if (previewPlayer == null) {
			return null;
		}
		PlayerTiers tiers = SpogTiersClient.cache().get(previewPlayer, list);
		if (tiers == null) {
			return null;
		}
		Tier shown = tierFor(element);
		for (var entry : tiers.all().entrySet()) {
			if (entry.getValue().equals(shown)) {
				return entry.getKey();
			}
		}
		return tiers.bestMode();
	}


	/**
	 * The tier an element would actually draw for the preview player.
	 *
	 * <p>Read from the cache and never fetched, since this runs on the render
	 * thread. Falls back to a stand-in only when nothing is known yet, so the
	 * row still has something to show and a width to measure.
	 */
	private Tier tierFor(TagLayout.Element element) {
		if (previewPlayer != null && element != null && !element.doorSmp) {
			PlayerTiers tiers = element.list() == null
					? SpogTiersClient.cache().get(previewPlayer)
					: SpogTiersClient.cache().get(previewPlayer, element.list());
			if (tiers != null) {
				Tier tier = element.gamemode == null ? tiers.best() : tiers.get(element.gamemode);
				if (tier != null && tier.isRanked()) {
					return tier;
				}
			}
		}
		return new Tier(1, Tier.Position.HIGH, false);
	}

	/** The grade our own list holds for the preview player, or null. */
	private PlayerGrade gradeForPreview() {
		return previewPlayer == null ? null : SpogTiersClient.service().grade(previewPlayer);
	}

	private int colour(TagLayout.Element element) {
		return switch (element.kind) {
			case NAME -> 0xFFFFFFFF;
			case REGION -> 0xFF89F19C;
			case SEPARATOR -> 0xFF000000 | element.colour;
			case TIER -> colourFor(element);
		};
	}

	/** The preview player's region where it is known, else a stand-in. */
	private String region() {
		if (previewPlayer == null) {
			return "EU";
		}
		String code = Regions.resolve(SpogTiersClient.cache().allLists(previewPlayer));
		return code.isEmpty() ? "EU" : code;
	}

	private void panel(DrawContext graphics, int left, int top, int right, int bottom) {
		graphics.fill(left, top, right, bottom, PANEL_FILL);
		outline(graphics, left, top, right, bottom, PANEL_BORDER);
	}

	/** A one-pixel border, the same weight on every edge. */
	private void outline(DrawContext graphics, int left, int top, int right,
			int bottom, int colour) {
		graphics.fill(left, top, right, top + 1, colour);
		graphics.fill(left, bottom - 1, right, bottom, colour);
		graphics.fill(left, top, left + 1, bottom, colour);
		graphics.fill(right - 1, top, right, bottom, colour);
	}

	/** A divider between groups, inset from both edges of the panel. */
	private int rule(DrawContext graphics, int left, int right, int y) {
		graphics.fill(left + PADDING, y + 2, right - PADDING, y + 3, PANEL_BORDER);
		return y + 9;
	}

	/** A button in the same style as Done and the rest of the screen. */
	private void pushButton(DrawContext graphics, String label, int x, int y,
			int width, int mouseX, int mouseY, String id) {
		int height = 20;
		boolean hovered = mouseX >= x && mouseX < x + width
				&& mouseY >= y && mouseY < y + height;
		graphics.fill(x, y, x + width, y + height, hovered ? 0x8022303F : 0x60161B22);
		outline(graphics, x, y, x + width, y + height,
				hovered ? 0xA05B6B7D : PANEL_BORDER);
		graphics.drawTextWithShadow(font, Text.literal(label),
				x + (width - font.getWidth(label)) / 2,
				y + (height - font.fontHeight) / 2, TEXT_COLOR);
		buttons.add(new Button(id, x, y, x + width, y + height));
	}

	/**
	 * The session's controls, drawn where Done sits on the other tabs.
	 *
	 * <p>Only Done: it is the one control here that is about the screen
	 * rather than about the tag.
	 */
	public void drawActions(DrawContext graphics, int x, int y, int width,
			int mouseX, int mouseY) {
		// Done alone here, in the plain style, because it only closes: the
		// layout is already saved by the time anyone reaches it, and the
		// session's own controls live beside the element creator instead.
		pushButton(graphics, "Done", x, y, width, mouseX, mouseY, "done");
	}

	/**
	 * How many fixed-width buttons fit across {@code width}, at least one and
	 * never more than {@code most}.
	 *
	 * <p>Never zero: a width too small even for one button still has to draw
	 * it, overhanging, rather than divide by zero or drop the control
	 * entirely. Somewhere to click beats nowhere.
	 */
	private static int fitPerRow(int width, int button, int gap, int most) {
		return Math.clamp((width + gap) / (button + gap), 1, most);
	}

	/**
	 * A button in the general tab's ON/OFF style: a dark tinted fill, a
	 * mid-brightness border and text in a light tint of the same hue.
	 *
	 * <p>Three shades of one colour rather than a solid block, which is what
	 * makes those switches read as controls rather than as coloured labels.
	 */
	private void colouredButton(DrawContext graphics, String label, int x, int y,
			int width, int mouseX, int mouseY, String id, Tint tint, String explains) {
		// Exactly as tall as the plus beside them, which is a square of the
		// same row's height. A pixel short of it left their bottom edges
		// visibly out of line along the row; the top edge is shared, so the
		// difference showed up entirely at the bottom.
		int height = 18;
		boolean hovered = mouseX >= x && mouseX < x + width
				&& mouseY >= y && mouseY < y + height;
		graphics.fill(x, y, x + width, y + height, hovered ? tint.hovered() : tint.fill());
		outline(graphics, x, y, x + width, y + height,
				hovered ? tint.text() : tint.border());
		// Centred on the height the button used to be, so shortening it moved
		// the edge and not the word.
		graphics.drawTextWithShadow(font, Text.literal(label),
				x + (width - font.getWidth(label)) / 2,
				y + (20 - font.fontHeight) / 2, tint.text());
		buttons.add(new Button(id, x, y, x + width, y + height));
		if (hovered && explains != null) {
			hoverButton = new Explained(explains, tint);
		}
	}





	/** A small square choice, lit when it is the current one. */
	private void pill(DrawContext graphics, String label, int x, int y, int size,
			int mouseX, int mouseY, String id, boolean picked) {
		boolean hovered = mouseX >= x && mouseX < x + size
				&& mouseY >= y && mouseY < y + size;
		graphics.fill(x, y, x + size, y + size, hovered ? 0x8022303F : 0x60161B22);
		outline(graphics, x, y, x + size, y + size, picked ? ACCENT : PANEL_BORDER);
		graphics.drawTextWithShadow(font, Text.literal(label),
				x + (size - font.getWidth(label)) / 2,
				y + (size - font.fontHeight) / 2, TEXT_COLOR);
		buttons.add(new Button(id, x, y, x + size, y + size));
	}

	/**
	 * The button that adds an element, in the same style as an ON switch.
	 *
	 * <p>Two bars thick rather than one, and built outward from the button's
	 * exact centre: an odd-width stroke on an even-width button sat a pixel
	 * off, which is what made it look askew.
	 */
	private void plus(DrawContext graphics, int x, int y, int size,
			int mouseX, int mouseY) {
		boolean hovered = mouseX >= x && mouseX < x + size
				&& mouseY >= y && mouseY < y + size;
		graphics.fill(x, y, x + size, y + size, hovered ? GREEN.hovered() : GREEN.fill());
		outline(graphics, x, y, x + size, y + size, hovered ? GREEN.text() : GREEN.border());

		// Both strokes share one centre and one thickness, so the cross is
		// symmetrical whichever way it is measured.
		int thickness = 2;
		int arm = size / 2 - 4;
		int cx = x + size / 2;
		int cy = y + size / 2;
		int half = thickness / 2;
		graphics.fill(cx - arm, cy - half, cx + arm, cy + thickness - half, GREEN.text());
		graphics.fill(cx - half, cy - arm, cx + thickness - half, cy + arm, GREEN.text());
		buttons.add(new Button("create", x, y, x + size, y + size));
		if (hovered) {
			hoverButton = new Explained("Create element of this type", GREEN);
		}
	}


	/**
	 * The button that deletes the selected element, in the same style as an
	 * OFF switch.
	 *
	 * <p>The bin is drawn about the button's centre -- a handle, a lid and a
	 * body with two staves -- each piece placed from the middle out, so it is
	 * symmetrical whatever the button's size. Hovering lifts the lid off the
	 * body, so the icon answers the pointer as much as the panel behind it.
	 */
	private void trash(DrawContext graphics, int x, int y, int size,
			int mouseX, int mouseY) {
		boolean hovered = mouseX >= x && mouseX < x + size
				&& mouseY >= y && mouseY < y + size;
		graphics.fill(x, y, x + size, y + size, hovered ? RED.hovered() : RED.fill());
		outline(graphics, x, y, x + size, y + size, hovered ? RED.text() : RED.border());

		int ink = RED.text();
		int cx = x + size / 2;
		// A pixel below centre: the lid and handle sit above `top`, so
		// measuring from the middle left the bin riding high in its button.
		int top = y + size / 2 - 4;
		int lift = hovered ? 2 : 0;
		// Handle and lid rise together; the body stays where it is.
		graphics.fill(cx - 2, top - 2 - lift, cx + 2, top - 1 - lift, ink);
		graphics.fill(cx - 5, top - lift, cx + 5, top + 1 - lift, ink);
		graphics.fill(cx - 4, top + 2, cx + 4, top + 3, ink);
		graphics.fill(cx - 4, top + 2, cx - 3, top + 10, ink);
		graphics.fill(cx + 3, top + 2, cx + 4, top + 10, ink);
		graphics.fill(cx - 4, top + 9, cx + 4, top + 10, ink);
		graphics.fill(cx - 2, top + 4, cx - 1, top + 8, ink);
		graphics.fill(cx + 1, top + 4, cx + 2, top + 8, ink);
		buttons.add(new Button("delete", x, y, x + size, y + size));
	}








	/** A labelled switch, returning the y to carry on from. */
	private int toggle(DrawContext graphics, String label, boolean on, int x, int y,
			int right, int mouseX, int mouseY, String id) {
		int height = 16;
		int boxWidth = 22;
		int boxX = right - boxWidth;
		graphics.drawTextWithShadow(font, Text.literal(label), x, y + 4, LABEL_COLOR);
		graphics.fill(boxX, y + 2, boxX + boxWidth, y + height - 2, on ? ON_FILL : OFF_FILL);
		int knob = on ? boxX + boxWidth - 10 : boxX + 2;
		graphics.fill(knob, y + 4, knob + 8, y + height - 4, 0xFFFFFFFF);
		buttons.add(new Button(id, x, y, right, y + height));
		return y + height + 6;
	}
}
