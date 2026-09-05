package com.spog.tiers.client.gui;

import com.spog.tiers.SpogTiersClient;
import com.spog.tiers.client.ModeIcons;
import com.spog.tiers.config.SpogTiersConfig;
import com.spog.tiers.config.TagLayout;
import com.spog.tiers.data.Gamemode;
import com.spog.tiers.data.Regions;
import com.spog.tiers.data.Tier;
import com.spog.tiers.data.TierList;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
	/** The player the preview is built around. */
	private static final UUID PREVIEW_PLAYER =
			UUID.fromString("ebd7af32-759e-41e2-b227-9eeb8576d609");
	private static final String PREVIEW_NAME = "Swight";

	private static final int PANEL_FILL = 0x50161B22;
	private static final int PANEL_BORDER = 0x70323B47;
	private static final int LABEL_COLOR = 0xFFB9C4D0;
	private static final int MUTED_COLOR = 0xFF6C7683;
	private static final int TEXT_COLOR = 0xFFD7DEE6;
	private static final int ACCENT = 0xFF6FC3E8;

	/** A switch reads as on or off by colour, not by position alone. */
	private static final int ON_FILL = 0xFF4CAF50;
	private static final int OFF_FILL = 0xFFC1443C;

	/** The two coloured controls: add and delete. */
	private static final int ADD_FILL = 0xFF3E8E41;
	private static final int DELETE_FILL = 0xFFB03A32;

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

	/** Which element the right panel is showing, or null for none. */
	private TagLayout.Element selected;

	/** Set when a save is refused, and cleared by the next change. */
	private String complaint;

	private final Font font;

	/** Where each element was last drawn, for hit testing and dropping. */
	private final List<Hit> hits = new ArrayList<>();

	/** Where each button was last drawn, likewise. */
	private final List<Button> buttons = new ArrayList<>();

	/** Where each row was last drawn, so a drag can change rows. */
	private final List<RowBand> bands = new ArrayList<>();

	private final Dropdown<TagLayout.Kind> creator;
	private final Dropdown<TierList> tierList;
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

	private record Hit(TagLayout.Element element, int left, int top, int right, int bottom) {
	}

	private record Button(String id, int left, int top, int right, int bottom) {
	}

	private record RowBand(TagLayout.Row row, int top, int bottom) {
	}

	public TagEditor(Font font, Runnable onChange) {
		this.font = font;
		this.onChange = onChange;
		this.creator = new Dropdown<>(value -> creating = value);
		this.tierList = new Dropdown<>(value -> {
			if (selected != null) {
				selected.list = value;
				changed();
			}
		});
		this.tierMode = new Dropdown<>(value -> {
			if (selected != null) {
				selected.gamemode = value;
				changed();
			}
		});
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
	public void draw(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom,
			int mouseX, int mouseY) {
		if (working == null) {
			open();
		}
		hits.clear();
		buttons.clear();
		bands.clear();

		int centreLeft = left + SIDE_WIDTH + PADDING;
		int centreRight = right - SIDE_WIDTH - PADDING;

		drawGeneral(graphics, left, top, left + SIDE_WIDTH, bottom, mouseX, mouseY);
		drawPreview(graphics, centreLeft, top, centreRight, bottom, mouseX, mouseY);
		drawElement(graphics, centreRight + PADDING, top, right, bottom, mouseX, mouseY);

		// The dragged element rides the pointer, drawn last so it is over
		// everything it might be dropped onto.
		if (dragging != null && dragMoved) {
			String text = preview(dragging);
			int width = font.width(text) + 6;
			int x = mouseX - width / 2;
			int y = mouseY - PREVIEW_ROW / 2;
			graphics.fill(x, y, x + width, y + PREVIEW_ROW, 0x90222A35);
			outline(graphics, x, y, x + width, y + PREVIEW_ROW, ACCENT);
			drawElementText(graphics, dragging, x + 3,
					y + (PREVIEW_ROW - font.lineHeight) / 2, true);
		}
	}

	/** Dropdown lists, drawn last so they sit over everything else. */
	public void drawOverlays(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		creator.drawOverlay(graphics, font, creating, mouseX, mouseY);
		if (selected != null && selected.kind == TagLayout.Kind.TIER) {
			tierList.drawOverlay(graphics, font, selected.list, mouseX, mouseY);
			tierMode.drawOverlay(graphics, font, selected.gamemode, mouseX, mouseY);
		}
	}

	// ---------------------------------------------------------------- panels

	private void drawGeneral(GuiGraphicsExtractor graphics, int left, int top, int right,
			int bottom, int mouseX, int mouseY) {
		SpogTiersConfig config = SpogTiersClient.config();
		panel(graphics, left, top, right, bottom);
		int x = left + PADDING;
		int y = top + PADDING;

		graphics.text(font, Component.literal("General"), x, y, LABEL_COLOR);
		y += font.lineHeight + 6;
		y = rule(graphics, left, right, y);

		y = toggle(graphics, "Enabled", config.enabled, x, y, right - PADDING,
				mouseX, mouseY, "toggle.enabled");
		y = rule(graphics, left, right, y);

		graphics.text(font, Component.literal("Show tiers in"), x, y, LABEL_COLOR);
		y += font.lineHeight + 6;
		y = toggle(graphics, "Nametag", config.showNametags, x, y, right - PADDING,
				mouseX, mouseY, "toggle.nametag");
		y = toggle(graphics, "Tab list", config.showTabList, x, y, right - PADDING,
				mouseX, mouseY, "toggle.tablist");
		y = toggle(graphics, "Chat", config.showInChat, x, y, right - PADDING,
				mouseX, mouseY, "toggle.chat");
		y = toggle(graphics, "Displays", config.tagDisplays, x, y, right - PADDING,
				mouseX, mouseY, "toggle.displays");
		y = rule(graphics, left, right, y);

		toggle(graphics, "No duplicates", config.preventDuplicateTiers, x, y, right - PADDING,
				mouseX, mouseY, "toggle.duplicates");
	}

	/**
	 * The tag itself, as three rows of elements that can be dragged.
	 *
	 * <p>Drawn from the same layout the game draws from, so what is arranged
	 * here is what appears over a player's head.
	 */
	private void drawPreview(GuiGraphicsExtractor graphics, int left, int top, int right,
			int bottom, int mouseX, int mouseY) {
		panel(graphics, left, top, right, bottom);

		int y = top + PADDING;
		graphics.text(font, Component.literal("Nametag"), left + PADDING, y, LABEL_COLOR);
		y += font.lineHeight + 8;

		// A dark plate behind the rows, so the tag reads the way it does in
		// the world rather than as three lines of loose text.
		int plateTop = y;
		int plateHeight = PREVIEW_ROW * 3 + 8;
		graphics.fill(left + PADDING, plateTop, right - PADDING, plateTop + plateHeight,
				0x60101720);

		int rowY = plateTop + 4;
		for (TagLayout.Row row : TagLayout.Row.values()) {
			bands.add(new RowBand(row, rowY, rowY + PREVIEW_ROW));
			drawRow(graphics, row, left + PADDING, rowY, right - PADDING, mouseX, mouseY);
			rowY += PREVIEW_ROW;
		}

		y = plateTop + plateHeight + 10;

		// Pick a kind and press plus; it lands at the end of the middle row
		// where it can be seen and then dragged.
		List<Dropdown.Entry<TagLayout.Kind>> kinds = new ArrayList<>();
		for (TagLayout.Kind kind : TagLayout.Kind.values()) {
			kinds.add(new Dropdown.Entry<>(kind, kind.title(), null));
		}
		int addSize = 18;
		int listWidth = Math.min(140, right - PADDING - addSize - 6 - (left + PADDING));
		creator.setEntries(kinds);
		creator.setBounds(left + PADDING, y, listWidth);
		creator.draw(graphics, font, creating, mouseX, mouseY);
		plus(graphics, left + PADDING + listWidth + 6, y, addSize, mouseX, mouseY);
		y += ROW_HEIGHT + 8;

		if (complaint != null) {
			graphics.text(font, Component.literal(complaint), left + PADDING, y, OFF_FILL);
		}

		// The session's controls, along the bottom, in the same style as Done.
		int barY = bottom - PADDING - 20;
		int wide = 62;
		pushButton(graphics, "Save", left + PADDING, barY, wide, mouseX, mouseY, "save");
		pushButton(graphics, "Cancel", left + PADDING + wide + 6, barY, wide,
				mouseX, mouseY, "cancel");
		pushButton(graphics, "Reset", left + PADDING + (wide + 6) * 2, barY, wide,
				mouseX, mouseY, "reset");
	}

	/** One row of the preview, and the hit boxes for its elements. */
	private void drawRow(GuiGraphicsExtractor graphics, TagLayout.Row row, int left, int y,
			int right, int mouseX, int mouseY) {
		List<TagLayout.Element> elements = working.row(row);
		int textY = y + (PREVIEW_ROW - font.lineHeight) / 2;

		// The row being dragged over is lit, so where a drop will land is
		// visible before letting go.
		boolean over = dragging != null && dragMoved
				&& mouseY >= y && mouseY < y + PREVIEW_ROW
				&& mouseX >= left && mouseX < right;
		if (over) {
			graphics.fill(left, y, right, y + PREVIEW_ROW, 0x20FFFFFF);
		}

		if (elements.isEmpty()) {
			// Named rather than left blank, so an empty row still reads as a
			// place something can be put.
			graphics.text(font, Component.literal(row.title() + " row"),
					left + 4, textY, 0x40FFFFFF);
			return;
		}

		// Centred, the way every row of a nameplate is drawn in the world.
		int width = 0;
		for (TagLayout.Element element : elements) {
			width += font.width(preview(element)) + 6;
		}
		int x = left + Math.max(4, ((right - left) - width) / 2);

		for (TagLayout.Element element : elements) {
			int span = font.width(preview(element)) + 6;
			boolean hovered = mouseX >= x && mouseX < x + span
					&& mouseY >= y && mouseY < y + PREVIEW_ROW;
			boolean chosen = element == selected;

			// Hover still needs a hint of a box to show it is grabbable, but
			// the selection itself is carried by the text, not a panel behind it.
			if (hovered && dragging == null) {
				graphics.fill(x, y + 1, x + span, y + PREVIEW_ROW - 1, 0x18FFFFFF);
			}
			if (element != dragging || !dragMoved) {
				drawElementText(graphics, element, x + 3, textY, chosen);
			}

			hits.add(new Hit(element, x, y, x + span, y + PREVIEW_ROW));
			x += span;
		}
	}

	/**
	 * One element's text, in the colours it will actually be drawn in.
	 *
	 * <p>A tier shows its list's icon and its own colour, so the preview says
	 * what the tag will look like rather than only what is in it. The
	 * selected element is drawn highlighted rather than boxed.
	 */
	private void drawElementText(GuiGraphicsExtractor graphics, TagLayout.Element element,
			int x, int y, boolean chosen) {
		if (element.kind == TagLayout.Kind.TIER) {
			MutableComponent text = Component.empty();
			Component icon = icon(element);
			if (icon != null && SpogTiersClient.config().showTagIcons) {
				text.append(icon).append(Component.literal(" "));
			}
			Tier sample = sampleTier();
			text.append(Component.literal(sample.label()));
			graphics.text(font, text, x, y, chosen ? 0xFFFFFFFF : sample.color());
			if (chosen) {
				underline(graphics, x, y, font.width(text));
			}
			return;
		}
		String plain = preview(element);
		graphics.text(font, Component.literal(plain), x, y,
				chosen ? 0xFFFFFFFF : colour(element));
		if (chosen) {
			underline(graphics, x, y, font.width(plain));
		}
	}

	/** The mark under a selected element, in place of a box around it. */
	private void underline(GuiGraphicsExtractor graphics, int x, int y, int width) {
		graphics.fill(x, y + font.lineHeight, x + width, y + font.lineHeight + 1, ACCENT);
	}

	/**
	 * The right panel: what the selected element can do.
	 *
	 * <p>Scrolls, because a tier element carries two dropdowns and a
	 * separator a palette, which is more than fits on a short screen.
	 */
	private void drawElement(GuiGraphicsExtractor graphics, int left, int top, int right,
			int bottom, int mouseX, int mouseY) {
		panel(graphics, left, top, right, bottom);
		int x = left + PADDING;
		int footer = bottom - FOOTER_HEIGHT;

		if (selected == null) {
			graphics.text(font, Component.literal("Nothing selected"), x, top + PADDING,
					MUTED_COLOR);
			scrollMax = 0;
			return;
		}

		int y = top + PADDING - scroll;
		graphics.text(font, Component.literal(selected.title()), x, y, LABEL_COLOR);
		y += font.lineHeight + 8;

		graphics.text(font, Component.literal("Drag to move it"), x, y, MUTED_COLOR);
		y += font.lineHeight + 10;

		if (selected.kind == TagLayout.Kind.TIER) {
			graphics.text(font, Component.literal("List"), x, y, MUTED_COLOR);
			y += font.lineHeight + 4;
			List<Dropdown.Entry<TierList>> lists = new ArrayList<>();
			lists.add(new Dropdown.Entry<>(null, "Best", null));
			for (TierList list : TierList.values()) {
				lists.add(new Dropdown.Entry<>(list, list.displayName(), null));
			}
			tierList.setEntries(lists);
			tierList.setBounds(x, y, right - x - PADDING);
			tierList.draw(graphics, font, selected.list, mouseX, mouseY);
			y += ROW_HEIGHT + 8;

			graphics.text(font, Component.literal("Gamemode"), x, y, MUTED_COLOR);
			y += font.lineHeight + 4;
			List<Dropdown.Entry<Gamemode>> modes = new ArrayList<>();
			modes.add(new Dropdown.Entry<>(null, "Best", null));
			for (Gamemode mode : Gamemode.values()) {
				modes.add(new Dropdown.Entry<>(mode, mode.displayName(), null));
			}
			tierMode.setEntries(modes);
			tierMode.setBounds(x, y, right - x - PADDING);
			tierMode.draw(graphics, font, selected.gamemode, mouseX, mouseY);
			y += ROW_HEIGHT + 8;
		}

		if (selected.kind == TagLayout.Kind.SEPARATOR) {
			graphics.text(font, Component.literal("Character"), x, y, MUTED_COLOR);
			y += font.lineHeight + 4;
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

			graphics.text(font, Component.literal("Colour"), x, y, MUTED_COLOR);
			y += font.lineHeight + 4;
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

		// The footer, and delete pinned to its right. Drawn after the content
		// so a long panel scrolls underneath rather than over it.
		graphics.fill(left + 1, footer, right - 1, footer + 1, PANEL_BORDER);
		graphics.fill(left + 1, footer + 1, right - 1, bottom - 1, 0x40101720);
		trash(graphics, right - PADDING - 18, footer + 4, 18, mouseX, mouseY);
	}

	// ---------------------------------------------------------------- input

	/** @return true when the click was handled here */
	public boolean click(double mouseX, double mouseY) {
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

		// Placed by where it was dropped along the row rather than appended:
		// dropping between two elements should put it between them.
		int at = 0;
		List<TagLayout.Element> row = working.row(target);
		for (TagLayout.Element element : row) {
			if (element == moved) {
				continue;
			}
			Hit hit = hitFor(element);
			if (hit != null && mouseX > (hit.left() + hit.right()) / 2.0) {
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
				TagLayout.Element element =
						new TagLayout.Element(creating, TagLayout.Row.MIDDLE);
				working.elements.add(element);
				selected = element;
				changed();
			}
			case "delete" -> {
				if (selected != null) {
					working.elements.remove(selected);
					selected = null;
					changed();
				}
			}
			case "save" -> save();
			case "cancel" -> {
				working = original.copy();
				selected = null;
				complaint = null;
			}
			case "reset" -> {
				working = TagLayout.defaults();
				selected = null;
				complaint = null;
			}
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
	 * Commits the working copy, unless it has no name.
	 *
	 * <p>Refused rather than corrected: silently adding a name back would
	 * leave the editor showing something the user did not arrange.
	 */
	private void save() {
		if (!working.hasName()) {
			complaint = "Needs a name element";
			return;
		}
		SpogTiersConfig config = SpogTiersClient.config();
		config.tagLayout = working.copy();
		config.save();
		original = working.copy();
		complaint = null;
	}

	private void changed() {
		complaint = null;
		if (onChange != null) {
			onChange.run();
		}
	}

	// ---------------------------------------------------------------- pieces

	/** What an element reads as in the preview. */
	private String preview(TagLayout.Element element) {
		return switch (element.kind) {
			case NAME -> PREVIEW_NAME;
			case REGION -> region();
			case SEPARATOR -> element.character;
			case TIER -> tierPreview(element);
		};
	}

	/** A tier's preview text, icon included, for measuring its width. */
	private String tierPreview(TagLayout.Element element) {
		Component icon = icon(element);
		String label = sampleTier().label();
		return icon != null && SpogTiersClient.config().showTagIcons
				? icon.getString() + " " + label : label;
	}

	/** The icon a tier element would draw, or null when it has none. */
	private Component icon(TagLayout.Element element) {
		TierList list = element.list == null ? TierList.PVPTIERS : element.list;
		Gamemode mode = element.gamemode;
		if (mode == null) {
			List<Gamemode> modes = List.copyOf(list.gamemodes());
			if (modes.isEmpty()) {
				return null;
			}
			mode = modes.get(0);
		}
		return ModeIcons.of(list, mode.key());
	}

	/** The tier the preview stands in with, so its colour is a real one. */
	private Tier sampleTier() {
		return new Tier(1, Tier.Position.HIGH, false);
	}

	private int colour(TagLayout.Element element) {
		return switch (element.kind) {
			case NAME -> 0xFFFFFFFF;
			case REGION -> 0xFF89F19C;
			case SEPARATOR -> 0xFF000000 | element.colour;
			case TIER -> sampleTier().color();
		};
	}

	/** His region where it is known, else a stand-in so the row still reads. */
	private String region() {
		String code = Regions.resolve(SpogTiersClient.cache().allLists(PREVIEW_PLAYER));
		return code.isEmpty() ? "EU" : code;
	}

	private void panel(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom) {
		graphics.fill(left, top, right, bottom, PANEL_FILL);
		outline(graphics, left, top, right, bottom, PANEL_BORDER);
	}

	/** A one-pixel border, the same weight on every edge. */
	private void outline(GuiGraphicsExtractor graphics, int left, int top, int right,
			int bottom, int colour) {
		graphics.fill(left, top, right, top + 1, colour);
		graphics.fill(left, bottom - 1, right, bottom, colour);
		graphics.fill(left, top, left + 1, bottom, colour);
		graphics.fill(right - 1, top, right, bottom, colour);
	}

	/** A divider between groups, inset from both edges of the panel. */
	private int rule(GuiGraphicsExtractor graphics, int left, int right, int y) {
		graphics.fill(left + PADDING, y + 2, right - PADDING, y + 3, PANEL_BORDER);
		return y + 9;
	}

	/** A button in the same style as Done and the rest of the screen. */
	private void pushButton(GuiGraphicsExtractor graphics, String label, int x, int y,
			int width, int mouseX, int mouseY, String id) {
		int height = 20;
		boolean hovered = mouseX >= x && mouseX < x + width
				&& mouseY >= y && mouseY < y + height;
		graphics.fill(x, y, x + width, y + height, hovered ? 0x8022303F : 0x60161B22);
		outline(graphics, x, y, x + width, y + height,
				hovered ? 0xA05B6B7D : PANEL_BORDER);
		graphics.text(font, Component.literal(label),
				x + (width - font.width(label)) / 2,
				y + (height - font.lineHeight) / 2, TEXT_COLOR);
		buttons.add(new Button(id, x, y, x + width, y + height));
	}

	/** A small square choice, lit when it is the current one. */
	private void pill(GuiGraphicsExtractor graphics, String label, int x, int y, int size,
			int mouseX, int mouseY, String id, boolean picked) {
		boolean hovered = mouseX >= x && mouseX < x + size
				&& mouseY >= y && mouseY < y + size;
		graphics.fill(x, y, x + size, y + size, hovered ? 0x8022303F : 0x60161B22);
		outline(graphics, x, y, x + size, y + size, picked ? ACCENT : PANEL_BORDER);
		graphics.text(font, Component.literal(label),
				x + (size - font.width(label)) / 2,
				y + (size - font.lineHeight) / 2, TEXT_COLOR);
		buttons.add(new Button(id, x, y, x + size, y + size));
	}

	/** The green square that adds an element. */
	private void plus(GuiGraphicsExtractor graphics, int x, int y, int size,
			int mouseX, int mouseY) {
		boolean hovered = mouseX >= x && mouseX < x + size
				&& mouseY >= y && mouseY < y + size;
		graphics.fill(x, y, x + size, y + size, hovered ? 0xFF4CAF50 : ADD_FILL);
		outline(graphics, x, y, x + size, y + size, 0xFF2E6B31);
		// Drawn rather than lettered, so it is a symbol at any font.
		int mid = size / 2;
		graphics.fill(x + 4, y + mid, x + size - 4, y + mid + 1, 0xFFFFFFFF);
		graphics.fill(x + mid, y + 4, x + mid + 1, y + size - 4, 0xFFFFFFFF);
		buttons.add(new Button("create", x, y, x + size, y + size));
	}

	/** The red square that deletes the selected element. */
	private void trash(GuiGraphicsExtractor graphics, int x, int y, int size,
			int mouseX, int mouseY) {
		boolean hovered = mouseX >= x && mouseX < x + size
				&& mouseY >= y && mouseY < y + size;
		graphics.fill(x, y, x + size, y + size, hovered ? 0xFFC1443C : DELETE_FILL);
		outline(graphics, x, y, x + size, y + size, 0xFF7A2924);

		// A lid, a handle and three staves: enough to read as a bin at 18px.
		int white = 0xFFFFFFFF;
		graphics.fill(x + 4, y + 5, x + size - 4, y + 6, white);
		graphics.fill(x + 7, y + 3, x + size - 7, y + 4, white);
		graphics.fill(x + 5, y + 6, x + size - 5, y + size - 4, 0x40FFFFFF);
		graphics.fill(x + 7, y + 8, x + 8, y + size - 6, white);
		graphics.fill(x + mid(size), y + 8, x + mid(size) + 1, y + size - 6, white);
		graphics.fill(x + size - 8, y + 8, x + size - 7, y + size - 6, white);
		buttons.add(new Button("delete", x, y, x + size, y + size));
	}

	private static int mid(int size) {
		return size / 2;
	}

	/** A labelled switch, returning the y to carry on from. */
	private int toggle(GuiGraphicsExtractor graphics, String label, boolean on, int x, int y,
			int right, int mouseX, int mouseY, String id) {
		int height = 16;
		int boxWidth = 22;
		int boxX = right - boxWidth;
		graphics.text(font, Component.literal(label), x, y + 4, LABEL_COLOR);
		graphics.fill(boxX, y + 2, boxX + boxWidth, y + height - 2, on ? ON_FILL : OFF_FILL);
		int knob = on ? boxX + boxWidth - 10 : boxX + 2;
		graphics.fill(knob, y + 4, knob + 8, y + height - 4, 0xFFFFFFFF);
		buttons.add(new Button(id, x, y, right, y + height));
		return y + height + 6;
	}
}
