package com.spog.tiers.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A small dropdown drawn in the panel style, with an optional icon per entry.
 *
 * <p>The open list is painted after everything else so it overlaps the rows
 * beneath it, which is why {@link #drawOverlay} is separate from {@link #draw}.
 */
public class Dropdown<T> {
	private static final int ROW_HEIGHT = 16;
	private static final int ICON_SIZE = 12;
	private static final int MAX_VISIBLE = 9;

	private static final int FILL = 0x50161B22;
	private static final int FILL_OPEN = 0xF00E1219;
	private static final int BORDER = 0x70323B47;
	private static final int BORDER_OPEN = 0xA05B6B7D;
	private static final int TEXT = 0xFFE4EAF2;
	private static final int TEXT_HOVER = 0xFFFFFFFF;

	private final List<Entry<T>> entries = new ArrayList<>();
	private final Consumer<T> onPick;

	private int x;
	private int y;
	private int width;
	private boolean open;
	private int scroll;

	public Dropdown(Consumer<T> onPick) {
		this.onPick = onPick;
	}

	public void setEntries(List<Entry<T>> values) {
		entries.clear();
		entries.addAll(values);
		scroll = 0;
	}

	public void setBounds(int x, int y, int width) {
		this.x = x;
		this.y = y;
		this.width = width;
	}

	public boolean isOpen() {
		return open;
	}

	public void close() {
		open = false;
	}

	public int height(Font font) {
		return font.lineHeight + 8;
	}

	/** The closed control: current value plus a caret. */
	public void draw(GuiGraphicsExtractor graphics, Font font, T current, int mouseX, int mouseY) {
		int boxHeight = height(font);
		boolean hovered = contains(mouseX, mouseY, x, y, width, boxHeight);

		frame(graphics, x, y, x + width, y + boxHeight,
				open ? FILL_OPEN : FILL, open || hovered ? BORDER_OPEN : BORDER);

		Entry<T> entry = find(current);
		int textX = x + 6;
		if (entry != null && entry.icon() != null) {
			graphics.blit(RenderPipelines.GUI_TEXTURED, entry.icon(), textX, y + 3,
					0.0f, 0.0f, ICON_SIZE, ICON_SIZE, 64, 64, 64, 64);
			textX += ICON_SIZE + 4;
		}

		String label = entry == null ? "-" : entry.label();
		graphics.text(font, Component.literal(trim(font, label, width - (textX - x) - 16)),
				textX, y + 4, hovered || open ? TEXT_HOVER : TEXT);

		// Caret, pointing the way the list will open.
		int caretX = x + width - 10;
		int caretY = y + boxHeight / 2 - 1;
		for (int i = 0; i < 3; i++) {
			graphics.fill(caretX - i, caretY + (open ? i : -i),
					caretX + i + 1, caretY + (open ? i : -i) + 1, 0xFF8A93A0);
		}
	}

	/** The open list, drawn last so it sits above neighbouring rows. */
	public void drawOverlay(GuiGraphicsExtractor graphics, Font font, T current, int mouseX, int mouseY) {
		if (!open || entries.isEmpty()) {
			return;
		}

		int visible = Math.min(MAX_VISIBLE, entries.size());
		int listTop = y + height(font) + 2;
		int listHeight = visible * ROW_HEIGHT + 4;

		frame(graphics, x, listTop, x + width, listTop + listHeight, FILL_OPEN, BORDER_OPEN);

		for (int i = 0; i < visible; i++) {
			Entry<T> entry = entries.get(i + scroll);
			int rowY = listTop + 2 + i * ROW_HEIGHT;
			boolean hovered = contains(mouseX, mouseY, x + 1, rowY, width - 2, ROW_HEIGHT);
			boolean selected = entry.value() == current
					|| (entry.value() != null && entry.value().equals(current));

			if (hovered) {
				graphics.fill(x + 1, rowY, x + width - 1, rowY + ROW_HEIGHT, 0x5022303F);
			} else if (selected) {
				graphics.fill(x + 1, rowY, x + width - 1, rowY + ROW_HEIGHT, 0x3016202B);
			}

			int textX = x + 6;
			if (entry.icon() != null) {
				graphics.blit(RenderPipelines.GUI_TEXTURED, entry.icon(), textX, rowY + 2,
						0.0f, 0.0f, ICON_SIZE, ICON_SIZE, 64, 64, 64, 64);
				textX += ICON_SIZE + 4;
			}
			graphics.text(font, Component.literal(
							trim(font, entry.label(), width - (textX - x) - 8)),
					textX, rowY + 4, hovered ? TEXT_HOVER : TEXT);
		}

		// Scrollbar, only when the list is longer than the window.
		if (entries.size() > visible) {
			int trackTop = listTop + 2;
			int trackHeight = visible * ROW_HEIGHT;
			int thumbHeight = Math.max(12, trackHeight * visible / entries.size());
			int thumbY = trackTop + (trackHeight - thumbHeight)
					* scroll / Math.max(1, entries.size() - visible);
			graphics.fill(x + width - 4, trackTop, x + width - 2, trackTop + trackHeight, 0x40202A38);
			graphics.fill(x + width - 4, thumbY, x + width - 2, thumbY + thumbHeight, 0x90727F8F);
		}
	}

	/** @return true when the click was consumed */
	public boolean click(Font font, double mouseX, double mouseY) {
		int boxHeight = height(font);
		if (contains((int) mouseX, (int) mouseY, x, y, width, boxHeight)) {
			open = !open;
			return true;
		}

		if (!open) {
			return false;
		}

		int visible = Math.min(MAX_VISIBLE, entries.size());
		int listTop = y + boxHeight + 2;
		for (int i = 0; i < visible; i++) {
			int rowY = listTop + 2 + i * ROW_HEIGHT;
			if (contains((int) mouseX, (int) mouseY, x + 1, rowY, width - 2, ROW_HEIGHT)) {
				onPick.accept(entries.get(i + scroll).value());
				open = false;
				return true;
			}
		}

		// Clicking away closes without picking.
		open = false;
		return true;
	}

	public boolean scroll(double mouseX, double mouseY, double amount, Font font) {
		if (!open || entries.size() <= MAX_VISIBLE) {
			return false;
		}
		int listTop = y + height(font) + 2;
		int listHeight = Math.min(MAX_VISIBLE, entries.size()) * ROW_HEIGHT + 4;
		if (!contains((int) mouseX, (int) mouseY, x, listTop, width, listHeight)) {
			return false;
		}
		scroll = Math.clamp(scroll - (int) Math.signum(amount), 0, entries.size() - MAX_VISIBLE);
		return true;
	}

	private Entry<T> find(T value) {
		for (Entry<T> entry : entries) {
			// null is a real choice here ("Best tier"), so match it explicitly
			// rather than falling through to the placeholder.
			if (entry.value() == null ? value == null : entry.value().equals(value)) {
				return entry;
			}
		}
		return null;
	}

	private static String trim(Font font, String text, int max) {
		String out = text;
		while (font.width(out) > max && out.length() > 1) {
			out = out.substring(0, out.length() - 1);
		}
		return out;
	}

	private static boolean contains(int mouseX, int mouseY, int left, int top, int w, int h) {
		return mouseX >= left && mouseX <= left + w && mouseY >= top && mouseY <= top + h;
	}

	private static void frame(GuiGraphicsExtractor graphics, int left, int top, int right,
			int bottom, int fill, int border) {
		graphics.fill(left, top, right, bottom, fill);
		graphics.fill(left, top, right, top + 1, border);
		graphics.fill(left, bottom - 1, right, bottom, border);
		graphics.fill(left, top, left + 1, bottom, border);
		graphics.fill(right - 1, top, right, bottom, border);
	}

	/** One row: a value, its label and an optional 64x64 icon texture. */
	public record Entry<T>(T value, String label, Identifier icon) {
	}
}
