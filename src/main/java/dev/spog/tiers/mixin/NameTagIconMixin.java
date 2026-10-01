package dev.spog.tiers.mixin;

import dev.spog.tiers.client.ModeIcons;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.NameTagFeatureRenderer;
import net.minecraft.client.renderer.feature.NameTagFeatureRenderer.Submit;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Draws a nameplate carrying one of our icons in two passes instead of one.
 *
 * <p>The font emits a line's backdrop first and its glyphs after, which is the
 * right order -- but only within one buffer. A backdrop and the ordinary
 * letters share the font atlas, so they batch together and that order holds.
 * Our icons do not: each is its own bitmap texture, so it resolves to its own
 * render type, lands in its own buffer and is flushed on its own. The
 * backdrop, queued on a different buffer, is free to be composited after it,
 * and the icon disappears behind the box that was meant to sit behind the
 * whole line. That is why a tag's text reads normally while its icon comes out
 * a black lozenge.
 *
 * <p>This version bakes the backdrop into the same prepared text as the
 * glyphs, so there is no draw call to split the way the other versions do.
 * What there is instead is the queue it works from: each plate is a record
 * carrying both its text and its backdrop, so an icon-bearing one is replaced
 * by two -- the box with no text, then the text with no box. The second cannot
 * paint over the first, whatever order the buffers are flushed in, because it
 * no longer has a box to paint with.
 *
 * <p>Only for plates that actually carry one of our glyphs. Every other
 * nameplate in the world -- every mob, every unranked player -- is left
 * exactly as vanilla queued it, so this costs nothing where it buys nothing.
 */
@Mixin(NameTagFeatureRenderer.class)
public class NameTagIconMixin {
	@Inject(method = "buildGroup", at = @At("HEAD"))
	private void spogtiers$splitIconPlates(FeatureFrameContext context,
			List<Submit> submits, CallbackInfo ci) {
		// Scanned before anything is allocated: the overwhelming majority of
		// frames have no tagged plate in them at all.
		boolean any = false;
		for (Submit submit : submits) {
			if (submit.backgroundColor() != 0 && carriesIcon(submit.text())) {
				any = true;
				break;
			}
		}
		if (!any) {
			return;
		}

		List<Submit> split = new ArrayList<>(submits.size() + 1);
		for (Submit submit : submits) {
			if (submit.backgroundColor() == 0 || !carriesIcon(submit.text())) {
				split.add(submit);
				continue;
			}
			// The box on its own. Empty text rather than a second copy of the
			// line: drawing the words twice would double every shadow and
			// leave the ones underneath showing through the pass above.
			split.add(new Submit(submit.pose(), submit.x(), submit.y(),
					Component.empty(), submit.lightCoords(), submit.color(),
					submit.backgroundColor(), submit.displayMode()));
			// Then the line with nothing behind it.
			split.add(new Submit(submit.pose(), submit.x(), submit.y(),
					submit.text(), submit.lightCoords(), submit.color(),
					0, submit.displayMode()));
		}
		submits.clear();
		submits.addAll(split);
	}

	/**
	 * Whether any part of a line is set in our icon font.
	 *
	 * <p>Checked on the style rather than the codepoint range: the private-use
	 * area belongs to whoever is using it, and another mod's glyph in it is
	 * not ours to go splitting plates for.
	 *
	 * <p>Walked with {@code visit} rather than by recursing over the siblings
	 * by hand: a sibling inherits its parent's font when it sets none of its
	 * own, so reading each component's own style would miss an icon whose
	 * style sits a level above it. {@code visit} resolves that inheritance and
	 * hands over the style each run is actually drawn with.
	 */
	private static boolean carriesIcon(Component text) {
		return text.visit((style, part) -> ModeIcons.FONT.equals(style.getFont())
				? Optional.of(Boolean.TRUE)
				: Optional.empty(), Style.EMPTY).isPresent();
	}
}
