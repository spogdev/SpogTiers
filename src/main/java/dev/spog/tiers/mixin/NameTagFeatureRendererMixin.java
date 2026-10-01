package dev.spog.tiers.mixin;

import dev.spog.tiers.client.ModeIcons;
import dev.spog.tiers.compat.NametagTweaks;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeStorage.NameTagSubmit;
import net.minecraft.client.renderer.feature.NameTagFeatureRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.joml.Matrix4fc;

import java.util.List;
import java.util.Optional;

/**
 * Moves a whole nameplate, not just its text, when Nametag Tweaks raises it.
 *
 * <p>That mod raises a plate by subtracting its offset from the {@code y} it
 * hands the font, at the moment of drawing. Anything else drawn from the
 * same nameplate -- Essential's icon, and the two backdrop-coloured strips it
 * pads the plate with -- positions itself from the plate's pose alone, so it
 * stays at the original height while the text and its box move away. With a
 * translucent black backdrop that left two faint bars beside the plate; with
 * a solid coloured one it left two solid bars.
 *
 * <p>Fixed here by folding the offset into the pose before any of that runs:
 * each queued plate is rebuilt with its pose moved up by the offset and its
 * {@code y} moved down by the same amount. The mod's own subtraction then
 * lands the text exactly where it did, and everything positioned from the
 * pose rises with it. Nothing changes without that mod, or when its offset is
 * zero.
 */
@Mixin(NameTagFeatureRenderer.class)
public class NameTagFeatureRendererMixin {
	@Inject(method = "renderTranslucent", at = @At("HEAD"))
	private void spogtiers$raiseWholePlate(SubmitNodeCollection collection,
			MultiBufferSource.BufferSource buffers, Font font, CallbackInfo ci) {
		float raise = NametagTweaks.plateOffset();
		if (raise == 0.0f) {
			return;
		}
		NameTagStorageAccessor storage = (NameTagStorageAccessor) collection.getNameTagSubmits();
		lift(storage.spogtiers$seeThrough(), raise);
		lift(storage.spogtiers$normal(), raise);
	}

	/**
	 * Draws a plate carrying one of our icons in two passes instead of one.
	 *
	 * <p>The font emits a plate's backdrop first and its glyphs after, which
	 * is the right order -- but only within one buffer. A backdrop and the
	 * ordinary letters share the font atlas, so they batch together and that
	 * order holds. Our icons do not: each is its own bitmap texture, so it
	 * resolves to its own render type, lands in its own buffer and is flushed
	 * on its own. The backdrop, queued on a different buffer, is free to be
	 * composited after it, and the icon disappears behind the box that was
	 * meant to sit behind the whole line. That is why a tag's text reads
	 * normally while its icon comes out a black lozenge.
	 *
	 * <p>So the two halves are drawn as two calls: the backdrop with no text,
	 * then the text with no backdrop. The second call cannot paint over the
	 * first, whatever order the buffers are flushed in, because it no longer
	 * has a box to paint with.
	 *
	 * <p>Only for plates that actually carry one of our glyphs. Every other
	 * nameplate in the world -- every mob, every unranked player -- is left on
	 * vanilla's single call, so this costs nothing where it buys nothing.
	 */
	@Redirect(method = "renderTranslucent",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/gui/Font;drawInBatch("
							+ "Lnet/minecraft/network/chat/Component;FFIZ"
							+ "Lorg/joml/Matrix4fc;"
							+ "Lnet/minecraft/client/renderer/MultiBufferSource;"
							+ "Lnet/minecraft/client/gui/Font$DisplayMode;II)V"))
	private void spogtiers$drawIconAbovePlate(Font font, Component text, float x, float y,
			int colour, boolean shadow, Matrix4fc pose, MultiBufferSource buffers,
			Font.DisplayMode mode, int background, int light) {
		if (background == 0 || !carriesIcon(text)) {
			font.drawInBatch(text, x, y, colour, shadow, pose, buffers, mode, background, light);
			return;
		}
		// The box on its own. Empty text rather than a second copy of the
		// line: drawing the words twice would double every shadow and leave
		// the ones underneath showing through the pass above.
		font.drawInBatch(Component.empty(), x, y, colour, shadow, pose, buffers,
				mode, background, light);
		// Then the line with nothing behind it.
		font.drawInBatch(text, x, y, colour, shadow, pose, buffers, mode, 0, light);
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

	/**
	 * Rebuilds each plate with the offset in its pose instead of its text.
	 *
	 * <p>The translate is in the pose's own space, where one unit is one font
	 * pixel and y runs downwards, so a negative y is upwards -- the same
	 * direction the mod's subtraction moves the text.
	 */
	private static void lift(List<NameTagSubmit> submits, float raise) {
		submits.replaceAll(submit -> new NameTagSubmit(
				new Matrix4f(submit.pose()).translate(0.0f, -raise, 0.0f),
				submit.x(), submit.y() + raise, submit.text(), submit.lightCoords(),
				submit.color(), submit.backgroundColor(), submit.distanceToCameraSq()));
	}
}
