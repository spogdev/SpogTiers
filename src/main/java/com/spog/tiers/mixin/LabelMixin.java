package com.spog.tiers.mixin;

import com.spog.tiers.util.AboveLabel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the above-name tag as its own label, stacked over the nameplate.
 *
 * <p>Submitted through the same {@code submitNameTag} vanilla uses for the
 * name, so the background, scale and fade are the game's own and wrap this
 * line's text rather than the wider of two lines.
 *
 * <p>The render state's {@code scoreText} would have been the obvious home,
 * but vanilla draws that <em>under</em> the name -- it is the scoreboard line.
 */
@Mixin(EntityRenderer.class)
public class LabelMixin {
	/**
	 * One line of vertical space, in the pixel units the offset argument takes.
	 *
	 * <p>Negative is up: vanilla passes this same argument through to
	 * {@code submitNameTag} for both of its own lines.
	 */
	private static final int LINE_OFFSET = -10;

	@Inject(method = "submitNameDisplay(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;"
			+ "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;"
			+ "Lnet/minecraft/client/renderer/state/level/CameraRenderState;I)V",
			at = @At("TAIL"))
	private void spogtiers$submitAboveLabel(EntityRenderState state, PoseStack poseStack,
			SubmitNodeCollector collector, CameraRenderState camera, int offset,
			CallbackInfo ci) {
		Component above = AboveLabel.get(state);
		if (above == null || state.nameTagAttachment == null) {
			return;
		}
		collector.submitNameTag(poseStack, state.nameTagAttachment, offset + LINE_OFFSET,
				above, !state.isDiscrete, state.lightCoords, state.distanceToCameraSq, camera);
	}
}
