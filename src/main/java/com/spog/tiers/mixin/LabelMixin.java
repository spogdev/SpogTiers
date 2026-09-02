package com.spog.tiers.mixin;

import com.spog.tiers.util.AboveLabel;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the above-name tag as its own label, stacked over the nameplate.
 *
 * <p>Submitted through the same {@code submitLabel} vanilla uses for the name,
 * so the background, scale and fade are the game's own and wrap this line's
 * text rather than the wider of two lines.
 */
@Mixin(EntityRenderer.class)
public class LabelMixin {
	/**
	 * One line of vertical space, matching what 26.x puts between its own two
	 * label lines: nine pixels at the label's scale.
	 */
	private static final float LINE_HEIGHT = 9.0f * 1.15f * 0.025f;

	@Inject(method = "renderLabelIfPresent", at = @At("TAIL"))
	private void spogtiers$submitAboveLabel(EntityRenderState state, MatrixStack matrices,
			OrderedRenderCommandQueue queue, CameraRenderState camera, CallbackInfo ci) {
		Text above = AboveLabel.get(state);
		if (above == null || state.nameLabelPos == null) {
			return;
		}

		matrices.push();
		// Negative is up. Vanilla draws its own upper line at 0 and then
		// translates by +LINE_HEIGHT to put the name underneath, so positive Y
		// here is downward -- translating the other way put this tag below the
		// name instead of above it.
		matrices.translate(0.0f, -LINE_HEIGHT, 0.0f);
		queue.submitLabel(matrices, state.nameLabelPos, 0, above,
				!state.sneaking, state.light, state.squaredDistanceToCamera, camera);
		matrices.pop();
	}
}
