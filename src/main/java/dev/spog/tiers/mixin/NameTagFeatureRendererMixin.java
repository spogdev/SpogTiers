package dev.spog.tiers.mixin;

import dev.spog.tiers.compat.NametagTweaks;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeStorage.NameTagSubmit;
import net.minecraft.client.renderer.feature.NameTagFeatureRenderer;
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
