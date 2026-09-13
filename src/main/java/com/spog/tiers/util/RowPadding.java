package com.spog.tiers.util;

import com.spog.tiers.SpogTiersClient;
import com.spog.tiers.client.ModeIcons;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

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
	public static int widest(Text plate, Text above, Text below) {
		if (!wanted()) {
			return 0;
		}
		TextRenderer font = MinecraftClient.getInstance().textRenderer;
		int widest = plate == null ? 0 : font.getWidth(plate);
		if (above != null) {
			widest = Math.max(widest, font.getWidth(above));
		}
		if (below != null) {
			widest = Math.max(widest, font.getWidth(below));
		}
		return widest;
	}

	/**
	 * The name plate padded to match the widest row.
	 *
	 * <p>Returned unchanged when the setting is off, which is also what keeps
	 * the plate exactly as vanilla built it for anyone not using this.
	 */
	public static Text plate(Text plate, Text above, Text below) {
		return pad(plate, widest(plate, above, below));
	}

	/**
	 * One row padded out to {@code width}, centred in it.
	 *
	 * <p>Half the padding either side rather than a run on the end, so the row
	 * stays centred on whatever it was centred on before.
	 */
	public static Text pad(Text row, int width) {
		if (row == null || width <= 0) {
			return row;
		}
		TextRenderer font = MinecraftClient.getInstance().textRenderer;
		int missing = width - font.getWidth(row);
		// A pixel is not worth a glyph: the narrowest padding available is two.
		if (missing <= 1) {
			return row;
		}
		MutableText out = Text.empty();
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
	private static Text spaces(int pixels) {
		MutableText out = Text.empty();
		int left = pixels;
		while (left >= 4) {
			out.append(Text.literal(" "));
			left -= 4;
		}
		if (left >= 2) {
			out.append(ModeIcons.narrowSpace());
		}
		return out;
	}
}
