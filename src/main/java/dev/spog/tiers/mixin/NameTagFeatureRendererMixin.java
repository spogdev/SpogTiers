package dev.spog.tiers.mixin;

import dev.spog.tiers.compat.NametagTweaks;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.command.BatchingRenderCommandQueue;
import net.minecraft.client.render.command.LabelCommandRenderer;
import net.minecraft.client.render.command.OrderedRenderCommandQueueImpl.LabelCommand;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

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
