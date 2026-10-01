package dev.spog.tiers.mixin;

import dev.spog.tiers.client.ModeIcons;
import dev.spog.tiers.compat.NametagTweaks;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.command.BatchingRenderCommandQueue;
import net.minecraft.client.render.command.LabelCommandRenderer;
import net.minecraft.client.render.command.OrderedRenderCommandQueueImpl.LabelCommand;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Optional;

/**
 * Moves a whole nameplate, not just its text, when Nametag Tweaks raises it.
 *
 * <p>That mod raises a plate by subtracting its offset from the {@code y} it
 * hands the font, at the moment of drawing. Anything else drawn from the
 * same nameplate -- Essential's icon, and the two backdrop-coloured strips it
 * pads the plate with -- positions itself from the plate's matrix alone, so it
 * stays at the original height while the text and its box move away. With a
 * translucent black backdrop that left two faint bars beside the plate; with
 * a solid coloured one it left two solid bars.
 *
 * <p>Fixed here by folding the offset into the matrix before any of that runs:
 * each queued plate is rebuilt with its matrix moved up by the offset and its
 * {@code y} moved down by the same amount. The mod's own subtraction then
 * lands the text exactly where it did, and everything positioned from the
 * matrix rises with it. Nothing changes without that mod, or when its offset
 * is zero.
 */
@Mixin(LabelCommandRenderer.class)
public class NameTagFeatureRendererMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void spogtiers$raiseWholePlate(BatchingRenderCommandQueue queue,
			VertexConsumerProvider.Immediate buffers, TextRenderer font, CallbackInfo ci) {
		float raise = NametagTweaks.plateOffset();
		if (raise == 0.0f) {
			return;
		}
		NameTagStorageAccessor commands = (NameTagStorageAccessor) queue.getLabelCommands();
		lift(commands.spogtiers$seeThrough(), raise);
		lift(commands.spogtiers$normal(), raise);
	}

	/**
	 * Draws a plate carrying one of our icons in two passes instead of one.
	 *
	 * <p>The font emits a line's backdrop first and its glyphs after, which is
	 * the right order -- but only within one buffer. A backdrop and the
	 * ordinary letters share the font atlas, so they batch together and that
	 * order holds. Our icons do not: each is its own bitmap texture, so it
	 * resolves to its own render layer, lands in its own buffer and is flushed
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
	@Redirect(method = "render",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/font/TextRenderer;draw("
							+ "Lnet/minecraft/text/Text;FFIZ"
							+ "Lorg/joml/Matrix4f;"
							+ "Lnet/minecraft/client/render/VertexConsumerProvider;"
							+ "Lnet/minecraft/client/font/TextRenderer$TextLayerType;II)V"))
	private void spogtiers$drawIconAbovePlate(TextRenderer font, Text text, float x, float y,
			int colour, boolean shadow, Matrix4f matrix, VertexConsumerProvider buffers,
			TextRenderer.TextLayerType layer, int background, int light) {
		if (background == 0 || !carriesIcon(text)) {
			font.draw(text, x, y, colour, shadow, matrix, buffers, layer, background, light);
			return;
		}
		// The box on its own. Empty text rather than a second copy of the
		// line: drawing the words twice would double every shadow and leave
		// the ones underneath showing through the pass above.
		font.draw(Text.empty(), x, y, colour, shadow, matrix, buffers, layer, background, light);
		// Then the line with nothing behind it.
		font.draw(text, x, y, colour, shadow, matrix, buffers, layer, 0, light);
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
	private static boolean carriesIcon(Text text) {
		return text.visit((style, part) -> ModeIcons.FONT.equals(style.getFont())
				? Optional.of(Boolean.TRUE)
				: Optional.empty(), Style.EMPTY).isPresent();
	}

	/**
	 * Rebuilds each plate with the offset in its matrix instead of its text.
	 *
	 * <p>The translate is in the matrix's own space, where one unit is one
	 * font pixel and y runs downwards, so a negative y is upwards -- the same
	 * direction the mod's subtraction moves the text.
	 */
	private static void lift(List<LabelCommand> commands, float raise) {
		commands.replaceAll(command -> new LabelCommand(
				new Matrix4f(command.matricesEntry()).translate(0.0f, -raise, 0.0f),
				command.x(), command.y() + raise, command.text(), command.lightCoords(),
				command.color(), command.backgroundColor(), command.distanceToCameraSq()));
	}
}
