package com.spog.tiers.client;

import com.spog.tiers.SpogTiers;
import com.spog.tiers.data.TierList;
import net.minecraft.text.Text;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.text.Style;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.Map;

/**
 * Gamemode icons as text.
 *
 * <p>Nametags, chat and the tab list are {@link Text}s, which can only
 * carry text -- there is no way to blit a texture into one. So every icon is
 * also published as a glyph in {@code assets/spogtiers/textRenderer/icons.json},
 * mapped onto the private-use area, and drawn by switching the style's textRenderer.
 *
 * <p>The codepoints are assigned in sorted order by the generator, so the same
 * ordering is reproduced here rather than hard-coding 40 constants.
 */
public final class ModeIcons {
	public static final StyleSpriteSource FONT = new StyleSpriteSource.Font(
			Identifier.of(SpogTiers.MOD_ID, "icons"));

	private static final int FIRST_CODEPOINT = 0xE000;

	/**
	 * The door, one past the last generated glyph.
	 *
	 * <p>Hard-coded on purpose: it belongs to no TierList, so the index
	 * arithmetic below cannot reach it. If a list ever gains a mode, this must
	 * move up with it -- there are 62 generated glyphs, E000 through E03D.
	 */
	private static final int DOOR_CODEPOINT = 0xE03E;

	/**
	 * The same door, raised a pixel, for nametags.
	 *
	 * <p>Vertical placement is a property of the font provider rather than the
	 * draw call, so a glyph that needs a different offset needs its own entry.
	 */
	private static final int DOOR_RAISED_CODEPOINT = 0xE03F;

	/**
	 * The Discord mark, for the row that carries a linked account.
	 *
	 * <p>The colour is baked into the texture rather than tinted at draw time:
	 * a bitmap glyph takes the style's colour, and leaving it to that would
	 * let a row's own colour repaint the logo.
	 */
	private static final int DISCORD_CODEPOINT = 0xE040;


	/**
	 * Modes per list, in the same sorted order the font generator walked, so
	 * index arithmetic lands on the right glyph.
	 */
	private static final Map<TierList, List<String>> MODES = Map.of(
			TierList.CATPVP, List.of(
					"axe", "beast", "bow", "bridge", "cart", "creeper", "dia_smp",
					"mace", "neth_pot", "pot", "smp", "spear_mace", "uhc", "vanilla"),
			TierList.MCPVP, List.of(
					"early_game", "end_game", "late_game", "mace", "mcpvp_spear",
					"pot", "shield", "sword"),
			TierList.MCTIERS, List.of(
					"axe", "mace", "neth_pot", "pot", "smp", "sword", "uhc", "vanilla"),
			TierList.PVPHQ, List.of(
					"axe", "cart", "crystal", "dia_smp", "mace", "neth_pot", "pot",
					"smp", "spear_mace", "sword", "uhc", "vanilla"),
			TierList.PVPTIERS, List.of(
					"axe", "crystal", "mace", "neth_pot", "pot", "smp", "sword", "uhc"),
			TierList.SUBTIERS, List.of(
					"bed", "bow", "creeper", "debuff", "dia_crystal", "dia_smp",
					"elytra", "manhunt", "minecart", "og_vanilla", "speed",
					"trident"));

	/** Lists in the order the generator saw them: sorted by directory name. */
	private static final List<TierList> ORDER = List.of(
			TierList.CATPVP, TierList.MCPVP, TierList.MCTIERS, TierList.PVPHQ,
			TierList.PVPTIERS, TierList.SUBTIERS);

	private ModeIcons() {
	}

	/**
	 * The icon for a gamemode as a styled component, or null when the list
	 * ships no artwork for it.
	 */
	public static Text of(TierList list, String modeKey) {
		int codepoint = codepointOf(list, modeKey);
		if (codepoint < 0) {
			return null;
		}
		return Text.literal(Character.toString(codepoint))
				.setStyle(Style.EMPTY.withFont(FONT));
	}

	/**
	 * The Door SMP door, for our own tierlist's tag.
	 *
	 * <p>Appended after every list's glyphs rather than slotted in
	 * alphabetically: the codepoints are index arithmetic over {@link #ORDER},
	 * so inserting one in the middle would shift every glyph after it and mean
	 * renumbering the whole font by hand. Adding at the end shifts nothing.
	 */
	public static Text door() {
		return Text.literal(Character.toString(DOOR_CODEPOINT))
				.setStyle(Style.EMPTY.withFont(FONT));
	}

	/** The door for a nametag, sitting a pixel higher than {@link #door()}. */
	public static Text doorRaised() {
		return Text.literal(Character.toString(DOOR_RAISED_CODEPOINT))
				.setStyle(Style.EMPTY.withFont(FONT));
	}

	/** The Discord mark, raised to sit level with the text beside it. */
	public static Text discord() {
		return Text.literal(Character.toString(DISCORD_CODEPOINT))
				.setStyle(Style.EMPTY.withFont(FONT));
	}

	/**
	 * A two-pixel gap, for after the door.
	 *
	 * <p>Lives in the default font rather than ours, alongside the icon widths
	 * the mod already publishes there, so it measures the same for anything
	 * else reading the label.
	 */
	private static final int NARROW_SPACE_CODEPOINT = 0xE0FF;

	/** Two pixels of blank space. */
	public static Text narrowSpace() {
		return Text.literal(Character.toString(NARROW_SPACE_CODEPOINT));
	}

	private static int codepointOf(TierList list, String modeKey) {
		String artwork = artworkKey(list, modeKey);
		int offset = 0;
		for (TierList candidate : ORDER) {
			List<String> modes = MODES.get(candidate);
			if (candidate == list) {
				int index = modes.indexOf(artwork);
				return index < 0 ? -1 : FIRST_CODEPOINT + offset + index;
			}
			offset += modes.size();
		}
		return -1;
	}

	/**
	 * The file a list's artwork for a mode is stored under.
	 *
	 * <p>Two lists have their own name for a mode everyone else shares --
	 * MCPvP's axe is "shield", and PvPTiers calls vanilla "crystal". Those
	 * resolve to one {@link Gamemode} now,
	 * so a lookup arrives under the shared name while the texture is still
	 * filed under the list's own. This maps back, and only for the list that
	 * uses that spelling.
	 *
	 * <p>The alternative was renaming the files, which would renumber every
	 * codepoint after them: the glyphs are indexed by sorted position, so one
	 * rename shifts every icon that sorts later.
	 */
	private static String artworkKey(TierList list, String modeKey) {
		return switch (list) {
			case MCPVP -> modeKey.equals("axe") ? "shield" : modeKey;
			case PVPTIERS -> modeKey.equals("vanilla") ? "crystal" : modeKey;
			default -> modeKey;
		};
	}
}
