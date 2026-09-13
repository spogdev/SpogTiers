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

	/**
	 * How much less the name line is padded when Essential is installed.
	 *
	 * <p>Its nameplate feature puts a backdrop strip either side of the label,
	 * so the plate finishes wider than the text we measured and the rows either
	 * side finish narrower. One pixel off the plate and one onto the rows is
	 * what closes that, which was measured rather than derived -- taking two off
	 * the plate overshot and pulled the box visibly inside the rows.
	 */
	private static final int ESSENTIAL_STRIPS = 1;

	/**
	 * Whether Essential is installed and drawing those strips.
	 *
	 * <p>The id is {@code essential-container}, which is what the jar declares
	 * -- "essential" matches nothing and would have left this silently doing
	 * nothing at all.
	 */
	private static boolean essential() {
		return net.fabricmc.loader.api.FabricLoader.getInstance()
				.isModLoaded("essential-container");
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
		int width = widest(plate, above, below);
		if (width <= 0) {
			return plate;
		}
		// Padded to less than the target, because Essential's own strips make
		// up the difference: padding it the full amount left the name line
		// wider than the rows either side by exactly those two pixels.
		return pad(plate, essential() ? width - ESSENTIAL_STRIPS : width);
	}

	/**
	 * One of the two extra rows, padded to match the name line.
	 *
	 * <p>A pixel wider than the measured width when Essential is installed:
	 * its strips widen the name line either side, so a row padded to the bare
	 * text measurement finishes just inside the plate it is meant to match.
	 */
	public static Text row(Text row, int width) {
		if (width <= 0) {
			return row;
		}
		return pad(row, essential() ? width + 1 : width);
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
		if (missing <= 0) {
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
	 * <p>The four, two and one pixel spaces from our own font include, so any
	 * width lands exactly rather than being rounded down.
	 */
	private static Text spaces(int pixels) {
		MutableText out = Text.empty();
		int left = pixels;
		while (left >= 4) {
			out.append(Text.literal(" "));
			left -= 4;
		}
		// Four, two and one, so any remainder lands exactly. With only the
		// four and the two, a one or three pixel remainder was dropped on each
		// side independently -- which is what left the rows short of the plate
		// rather than level with it.
		if (left >= 2) {
			out.append(ModeIcons.narrowSpace());
			left -= 2;
		}
		if (left >= 1) {
			out.append(ModeIcons.hairSpace());
		}
		return out;
	}
}
