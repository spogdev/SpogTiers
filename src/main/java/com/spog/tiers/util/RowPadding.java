package com.spog.tiers.util;

import com.spog.tiers.SpogTiersClient;
import com.spog.tiers.client.ModeIcons;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Pads the nametag's rows out to a common width.
 *
 * <p>Each row's backdrop comes from the font and is only as wide as the text it
 * is handed, so three rows of different lengths stack up as a ragged set of
 * boxes. Auto Expand pads the shorter ones until the three read as one plate.
 *
 * <p>Shared between the plate and the two extra rows because they are set in
 * different places -- the plate on the render state, the rows when they are
 * drawn -- and padding measured two different ways would not line up.
 */
public final class RowPadding {
	private RowPadding() {
	}

	/** Whether Auto Expand is on. */
	private static boolean wanted() {
		var config = SpogTiersClient.config();
		return config != null && config.tagLayout != null && config.tagLayout.autoExpand;
	}

	/** The widest of the three rows, or zero when the setting is off. */
	public static int widest(Component plate, Component above, Component below) {
		if (!wanted()) {
			return 0;
		}
		Font font = Minecraft.getInstance().font;
		int widest = plate == null ? 0 : font.width(plate);
		if (above != null) {
			widest = Math.max(widest, font.width(above));
		}
		if (below != null) {
			widest = Math.max(widest, font.width(below));
		}
		return widest;
	}

	/**
	 * The name plate padded to match the widest row.
	 *
	 * <p>Returned unchanged when the setting is off, which is also what keeps
	 * the plate exactly as vanilla built it for anyone not using this.
	 */
	public static Component plate(Component plate, Component above, Component below) {
		return pad(plate, widest(plate, above, below));
	}

	/**
	 * One row padded out to {@code width}, centred in it.
	 *
	 * <p>Half the padding either side rather than a run on the end, so the row
	 * stays centred on whatever it was centred on before.
	 */
	public static Component pad(Component row, int width) {
		if (row == null || width <= 0) {
			return row;
		}
		Font font = Minecraft.getInstance().font;
		int missing = width - font.width(row);
		// A pixel is not worth a glyph: the narrowest padding available is two.
		if (missing <= 1) {
			return row;
		}
		MutableComponent out = Component.empty();
		// The odd pixel goes left, matching how the editor lays a row out.
		out.append(spaces(missing - missing / 2));
		out.append(row);
		out.append(spaces(missing / 2));
		return out;
	}

	/**
	 * A run of padding as close to {@code pixels} wide as the spaces allow.
	 *
	 * <p>The four pixel space and the two pixel one from our own font include,
	 * so a row lands within a pixel of the target rather than being rounded to
	 * the nearest four.
	 */
	private static Component spaces(int pixels) {
		MutableComponent out = Component.empty();
		int left = pixels;
		while (left >= 4) {
			out.append(Component.literal(" "));
			left -= 4;
		}
		if (left >= 2) {
			out.append(ModeIcons.narrowSpace());
		}
		return out;
	}
}
