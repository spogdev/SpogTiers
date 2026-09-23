package dev.spog.tiers.client;

import dev.spog.tiers.config.SpogTiersConfig;
import dev.spog.tiers.data.Gamemode;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

import java.util.EnumMap;
import java.util.Map;

/**
 * Tier icons drawn as font glyphs rather than as each list's own artwork.
 *
 * <p>The glyphs are the ones Lunar Client's tier tagger uses, read from its
 * own {@code tier-tagger.json} rather than guessed at. Two styles share them:
 * Lunar keeps its colours as well, Classic recolours them to the accent each
 * gamemode already uses elsewhere in this mod, which is what the PvPTiers
 * tagger does.
 *
 * <p>A mode with no glyph -- the ones only CatPVP and MCPvP rank -- keeps the
 * artwork of whichever list it came from, so a setting never costs an icon.
 */
public final class GlyphIcons {
	private record Glyph(String text, int colour) {
	}

	private static final Map<Gamemode, Glyph> GLYPHS = new EnumMap<>(Gamemode.class);

	static {
		// Lunar's own pairings, mode for mode. The surrogate pairs are the
		// emoji it uses on 1.16 and later; every branch here is well past that,
		// so its older fallbacks are not carried.
		glyph(Gamemode.SWORD, "🗡", 0x6195D9);
		glyph(Gamemode.UHC, "❤", 0xCD484A);
		glyph(Gamemode.POT, "⚗", 0xD65474);
		glyph(Gamemode.NETH_POT, "☠", 0x6E4A91);
		glyph(Gamemode.SMP, "⛨", 0x10564A);
		glyph(Gamemode.AXE, "🪓", 0x71BFDD);
		glyph(Gamemode.MACE, "🔨", 0x8C8CB2);
		glyph(Gamemode.VANILLA, "✦", 0xC889E6);
		glyph(Gamemode.DIA_SMP, "⛨", 0x8E658C);
		glyph(Gamemode.ELYTRA, "✯", 0x533D4F);
		glyph(Gamemode.BEDWARS, "BW", 0x8C1616);
		glyph(Gamemode.DIA_CRYSTAL, "✦", 0x66C4FF);
		glyph(Gamemode.BED, "☽", 0xB23029);
		glyph(Gamemode.BOW, "🏹", 0x92715D);
		glyph(Gamemode.CREEPER, "✴", 0x89DF89);
		glyph(Gamemode.DEBUFF, "⚗", 0xE5B746);
		glyph(Gamemode.MANHUNT, "☠", 0x4E4E4E);
		glyph(Gamemode.MINECART, "☄", 0xCD484A);
		glyph(Gamemode.OG_VANILLA, "❤", 0xE9B750);
		glyph(Gamemode.SPEED, "🧪", 0x6DC4CD);
		glyph(Gamemode.TRIDENT, "🔱", 0x42957E);
		glyph(Gamemode.BRIDGE, "BD", 0x4C3B5C);
	}

	private GlyphIcons() {
	}

	private static void glyph(Gamemode mode, String text, int colour) {
		GLYPHS.put(mode, new Glyph(text, colour));
	}

	/** Whether a style draws glyphs at all. */
	public static boolean active(SpogTiersConfig.IconStyle style) {
		return style == SpogTiersConfig.IconStyle.LUNAR
				|| style == SpogTiersConfig.IconStyle.CLASSIC;
	}

	/**
	 * The glyph for a mode, or null when there is none for it.
	 *
	 * <p>Null rather than a placeholder: the caller falls back to the list's
	 * own artwork, which is better than a box for a mode Lunar never ranked.
	 */
	public static Component of(SpogTiersConfig.IconStyle style, Gamemode mode) {
		if (mode == null || !active(style)) {
			return null;
		}
		Glyph glyph = GLYPHS.get(mode);
		if (glyph == null) {
			return null;
		}
		// Classic keeps the shapes and takes the colour the rest of the mod
		// already gives that mode, so the icons match the tier text beside
		// them rather than bringing a second palette with them.
		int colour = style == SpogTiersConfig.IconStyle.CLASSIC
				? mode.accent()
				: 0xFF000000 | glyph.colour();
		return Component.literal(glyph.text())
				.setStyle(Style.EMPTY.withColor(colour));
	}
}
