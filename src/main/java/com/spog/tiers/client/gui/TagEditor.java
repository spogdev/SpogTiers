package com.spog.tiers.client.gui;

import com.spog.tiers.SpogTiersClient;
import com.spog.tiers.config.SpogTiersConfig;
import com.spog.tiers.config.TagLayout;
import com.spog.tiers.data.Gamemode;
import com.spog.tiers.data.Regions;
import com.spog.tiers.data.TierList;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The nametag tab: a live tag in the middle, settings either side.
 *
 * <p>Replaces three fixed rows of dropdowns with the tag itself. Everything
 * drawn in the centre is an element that can be clicked, and the right panel
 * then shows what that element can do -- so what a setting affects is never in
 * doubt, because the thing it affects is what was clicked.
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
	private static final int ACCENT = 0xFF6FC3E8;
	private static final int DANGER = 0xFFD46A6A;
	private static final int ROW_HEIGHT = 22;
	private static final int PADDING = 10;

	/** How wide the side panels are; the centre takes what is left. */
	private static final int SIDE_WIDTH = 132;

	/** The bar at the bottom of the right panel that the delete button is pinned to. */
	private static final int FOOTER_HEIGHT = 26;

	/** The layout being edited, and the one to fall back to on cancel. */
	private TagLayout working;
	private TagLayout original;

	/** Which element the right panel is showing, or null for none. */
	private TagLayout.Element selected;

	/** Set when a save is refused, and cleared by the next change. */
	private String complaint;

	private final Font font;

	/** Where each element was last drawn, for hit testing. */
	private final List<Hit> hits = new ArrayList<>();

	/** Where each button was last drawn, likewise. */
	private final List<Button> buttons = new ArrayList<>();

	private final Dropdown<TagLayout.Kind> creator;
	private final Dropdown<TierList> tierList;
	private final Dropdown<Gamemode> tierMode;

	/** What to create next, and how far the right panel is scrolled. */
	private TagLayout.Kind creating = TagLayout.Kind.TIER;
	private int scroll;
	private int scrollMax;

	private final Runnable onChange;

	private record Hit(TagLayout.Element element, int left, int top, int right, int bottom) {
	}

	private record Button(String id, int left, int top, int right, int bottom) {
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

		int centreLeft = left + SIDE_WIDTH + PADDING;
		int centreRight = right - SIDE_WIDTH - PADDING;

		drawGeneral(graphics, left, top, left + SIDE_WIDTH, bottom, mouseX, mouseY);
		drawPreview(graphics, centreLeft, top, centreRight, bottom, mouseX, mouseY);
		drawElement(graphics, centreRight + PADDING, top, right, bottom, mouseX, mouseY);
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

		graphics.text(font, Component.literal("Tier Tagger"), x, y, LABEL_COLOR);
		y += font.lineHeight + 6;

		y = toggle(graphics, "Enabled", config.enabled, x, y, right - PADDING,
				mouseX, mouseY, "toggle.enabled");
		y += 6;

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
		y += 6;

		toggle(graphics, "No duplicates", config.preventDuplicateTiers, x, y, right - PADDING,
				mouseX, mouseY, "toggle.duplicates");
	}

	/**
	 * The tag itself, as three rows of clickable elements.
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
		int plateHeight = ROW_HEIGHT * 3 + 8;
		graphics.fill(left + PADDING, plateTop, right - PADDING, plateTop + plateHeight,
				0x60101720);

		int rowY = plateTop + 4;
		for (TagLayout.Row row : TagLayout.Row.values()) {
			drawRow(graphics, row, left + PADDING, rowY, right - PADDING, mouseX, mouseY);
			rowY += ROW_HEIGHT;
		}

		y = plateTop + plateHeight + 10;

		// The creator: pick a kind, press Create, and it lands at the end of
		// the middle row where it can be seen and then moved.
		List<Dropdown.Entry<TagLayout.Kind>> kinds = new ArrayList<>();
		for (TagLayout.Kind kind : TagLayout.Kind.values()) {
			kinds.add(new Dropdown.Entry<>(kind, kind.title(), null));
		}
		creator.setEntries(kinds);
		creator.setBounds(left + PADDING, y, 110);
		creator.draw(graphics, font, creating, mouseX, mouseY);
		button(graphics, "Create", left + PADDING + 116, y, 60, mouseX, mouseY,
				"create", ACCENT);
		y += ROW_HEIGHT + 10;

		if (complaint != null) {
			graphics.text(font, Component.literal(complaint), left + PADDING, y, DANGER);
		}

		// The session's controls, along the bottom.
		int barY = bottom - PADDING - 18;
		button(graphics, "Save", left + PADDING, barY, 60, mouseX, mouseY, "save", ACCENT);
		button(graphics, "Cancel", left + PADDING + 66, barY, 60, mouseX, mouseY,
				"cancel", LABEL_COLOR);
		button(graphics, "Reset", left + PADDING + 132, barY, 60, mouseX, mouseY,
				"reset", MUTED_COLOR);
	}

	/** One row of the preview, and the hit boxes for its elements. */
	private void drawRow(GuiGraphicsExtractor graphics, TagLayout.Row row, int left, int y,
			int right, int mouseX, int mouseY) {
		List<TagLayout.Element> elements = working.row(row);
		int textY = y + (ROW_HEIGHT - font.lineHeight) / 2;

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
			String text = preview(element);
			int span = font.width(text) + 6;
			boolean hovered = mouseX >= x && mouseX < x + span
					&& mouseY >= y && mouseY < y + ROW_HEIGHT;

			if (element == selected) {
				graphics.fill(x, y + 1, x + span, y + ROW_HEIGHT - 1, 0x503B82C4);
				graphics.fill(x, y + 1, x + span, y + 2, ACCENT);
				graphics.fill(x, y + ROW_HEIGHT - 2, x + span, y + ROW_HEIGHT - 1, ACCENT);
			} else if (hovered) {
				graphics.fill(x, y + 1, x + span, y + ROW_HEIGHT - 1, 0x30FFFFFF);
			}
			graphics.text(font, Component.literal(text), x + 3, textY, colour(element));

			hits.add(new Hit(element, x, y, x + span, y + ROW_HEIGHT));
			x += span;
		}
	}

	/**
	 * The right panel: what the selected element can do.
	 *
	 * <p>Scrolls, because a tier element carries two dropdowns and the move
	 * buttons, which is more than fits on a short screen.
	 */
	private void drawElement(GuiGraphicsExtractor graphics, int left, int top, int right,
			int bottom, int mouseX, int mouseY) {
		panel(graphics, left, top, right, bottom);
		int x = left + PADDING;
		int footer = bottom - FOOTER_HEIGHT;

		if (selected == null) {
			graphics.text(font, Component.literal("Nothing selected"), x, top + PADDING,
					MUTED_COLOR);
			graphics.text(font, Component.literal("Click a piece of"), x,
					top + PADDING + font.lineHeight + 6, MUTED_COLOR);
			graphics.text(font, Component.literal("the tag to edit it."), x,
					top + PADDING + (font.lineHeight + 6) * 2, MUTED_COLOR);
			scrollMax = 0;
			return;
		}

		int y = top + PADDING - scroll;
		graphics.text(font, Component.literal(selected.title()), x, y, LABEL_COLOR);
		y += font.lineHeight + 8;

		// Four arrows: two to change row, two to move along it. Labelled with
		// triangles so the direction is the label.
		graphics.text(font, Component.literal("Move"), x, y, MUTED_COLOR);
		y += font.lineHeight + 4;
		int arrow = 26;
		button(graphics, "▲", x, y, arrow, mouseX, mouseY, "move.up", LABEL_COLOR);
		button(graphics, "▼", x + arrow + 4, y, arrow, mouseX, mouseY,
				"move.down", LABEL_COLOR);
		y += ROW_HEIGHT + 2;
		button(graphics, "◀", x, y, arrow, mouseX, mouseY, "move.left", LABEL_COLOR);
		button(graphics, "▶", x + arrow + 4, y, arrow, mouseX, mouseY,
				"move.right", LABEL_COLOR);
		y += ROW_HEIGHT + 10;

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
			String[] choices = {"|", "/", "-", "·", "•", ":", " "};
			int cx = x;
			for (String choice : choices) {
				boolean picked = choice.equals(selected.character);
				button(graphics, choice.isBlank() ? "␣" : choice, cx, y, 20,
						mouseX, mouseY, "char." + choice, picked ? ACCENT : LABEL_COLOR);
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
				boolean picked = colour == selected.colour;
				graphics.fill(sx, y, sx + 18, y + 18, 0xFF000000 | colour);
				if (picked) {
					graphics.fill(sx - 1, y - 1, sx + 19, y, ACCENT);
					graphics.fill(sx - 1, y + 18, sx + 19, y + 19, ACCENT);
					graphics.fill(sx - 1, y - 1, sx, y + 19, ACCENT);
					graphics.fill(sx + 18, y - 1, sx + 19, y + 19, ACCENT);
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

		// The footer, and the delete button pinned to it. Drawn after the
		// content so a long panel scrolls underneath rather than over it.
		graphics.fill(left + 1, footer, right - 1, footer + 1, PANEL_BORDER);
		graphics.fill(left + 1, footer + 1, right - 1, bottom - 1, 0x40101720);
		button(graphics, "Delete", x, footer + 4, right - x - PADDING, mouseX, mouseY,
				"delete", DANGER);
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
				return true;
			}
		}
		return false;
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
			case "move.up" -> move(false);
			case "move.down" -> move(true);
			case "move.left" -> {
				if (selected != null) {
					working.shift(selected, false);
					changed();
				}
			}
			case "move.right" -> {
				if (selected != null) {
					working.shift(selected, true);
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

	private void move(boolean down) {
		if (selected != null) {
			working.reRow(selected, down);
			changed();
		}
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
			case TIER -> "HT1";
		};
	}

	private int colour(TagLayout.Element element) {
		return switch (element.kind) {
			case NAME -> 0xFFFFFFFF;
			case REGION -> 0xFF89F19C;
			case SEPARATOR -> 0xFF000000 | element.colour;
			case TIER -> 0xFF6FC3E8;
		};
	}

	/** His region where it is known, else a stand-in so the row still reads. */
	private String region() {
		String code = Regions.resolve(SpogTiersClient.cache().allLists(PREVIEW_PLAYER));
		return code.isEmpty() ? "EU" : code;
	}

	private void panel(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom) {
		graphics.fill(left, top, right, bottom, PANEL_FILL);
		graphics.fill(left, top, right, top + 1, PANEL_BORDER);
		graphics.fill(left, bottom - 1, right, bottom, PANEL_BORDER);
		graphics.fill(left, top, left + 1, bottom, PANEL_BORDER);
		graphics.fill(right - 1, top, right, bottom, PANEL_BORDER);
	}

	private void button(GuiGraphicsExtractor graphics, String label, int x, int y, int width,
			int mouseX, int mouseY, String id, int colour) {
		int height = 18;
		boolean hovered = mouseX >= x && mouseX < x + width
				&& mouseY >= y && mouseY < y + height;
		graphics.fill(x, y, x + width, y + height, hovered ? 0x50FFFFFF : 0x30FFFFFF);
		graphics.text(font, Component.literal(label),
				x + (width - font.width(label)) / 2,
				y + (height - font.lineHeight) / 2 + 1, colour);
		buttons.add(new Button(id, x, y, x + width, y + height));
	}

	/** A labelled switch, returning the y to carry on from. */
	private int toggle(GuiGraphicsExtractor graphics, String label, boolean on, int x, int y,
			int right, int mouseX, int mouseY, String id) {
		int height = 16;
		int boxWidth = 22;
		int boxX = right - boxWidth;
		graphics.text(font, Component.literal(label), x, y + 4, LABEL_COLOR);
		graphics.fill(boxX, y + 2, boxX + boxWidth, y + height - 2,
				on ? 0xFF3B82C4 : 0x40FFFFFF);
		int knob = on ? boxX + boxWidth - 10 : boxX + 2;
		graphics.fill(knob, y + 4, knob + 8, y + height - 4, 0xFFFFFFFF);
		buttons.add(new Button(id, x, y, right, y + height));
		return y + height + 6;
	}
}
