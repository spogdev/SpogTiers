package com.spog.tiers.client;

import com.spog.tiers.SpogTiers;
import com.spog.tiers.data.TierList;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Map;

/**
 * Gamemode icons as text.
 *
 * <p>Nametags, chat and the tab list are {@link Component}s, which can only
 * carry text -- there is no way to blit a texture into one. So every icon is
 * also published as a glyph in {@code assets/spogtiers/font/icons.json},
 * mapped onto the private-use area, and drawn by switching the style's font.
 *
 * <p>The codepoints are assigned in sorted order by the generator, so the same
 * ordering is reproduced here rather than hard-coding 40 constants.
 */
public final class ModeIcons {
	public static final FontDescription FONT = new FontDescription.Resource(
			Identifier.fromNamespaceAndPath(SpogTiers.MOD_ID, "icons"));

	private static final int FIRST_CODEPOINT = 0xE000;

	/**
	 * Modes per list, in the same sorted order the font generator walked, so
	 * index arithmetic lands on the right glyph.
	 */
	private static final Map<TierList, List<String>> MODES = Map.of(
			TierList.CATPVP, List.of(
					"axe", "beast", "bow", "bridge", "cart", "creeper", "dia_smp",
					"mace", "neth_pot", "pot", "smp", "spear_mace", "uhc", "vanilla"),
			TierList.MCPVP, List.of(
					"axe", "mace", "neth_pot", "pot", "smp", "sword", "uhc", "vanilla"),
			TierList.MCTIERS, List.of(
					"axe", "mace", "neth_pot", "pot", "smp", "sword", "uhc", "vanilla"),
			TierList.PVPHQ, List.of(
					"axe", "cart", "crystal", "dia_smp", "mace", "neth_pot", "pot",
					"smp", "spear_mace", "sword", "uhc"),
			TierList.PVPTIERS, List.of(
					"axe", "crystal", "mace", "neth_pot", "pot", "smp", "sword", "uhc"),
			TierList.SUBTIERS, List.of(
					"bed", "bow", "creeper", "debuff", "dia_crystal", "dia_smp",
					"elytra", "mace", "manhunt", "minecart", "og_vanilla", "speed",
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
	public static Component of(TierList list, String modeKey) {
		int codepoint = codepointOf(list, modeKey);
		if (codepoint < 0) {
			return null;
		}
		return Component.literal(Character.toString(codepoint))
				.setStyle(Style.EMPTY.withFont(FONT));
	}

	private static int codepointOf(TierList list, String modeKey) {
		int offset = 0;
		for (TierList candidate : ORDER) {
			List<String> modes = MODES.get(candidate);
			if (candidate == list) {
				int index = modes.indexOf(modeKey);
				return index < 0 ? -1 : FIRST_CODEPOINT + offset + index;
			}
			offset += modes.size();
		}
		return -1;
	}
}
