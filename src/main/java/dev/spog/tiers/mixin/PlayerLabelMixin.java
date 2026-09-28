package dev.spog.tiers.mixin;

import dev.spog.tiers.client.render.AboveLabelRenderer;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The player half of {@link LabelMixin}, which explains why this is separate.
 *
 * <p>Players are the only thing we actually tag, so this is the mixin that
 * matters; the descriptor is spelled out so it binds the real override rather
 * than the bridge method the compiler emits beside it.
 */
@Mixin(PlayerEntityRenderer.class)
public class PlayerLabelMixin {
	@Inject(method = "renderLabelIfPresent(Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;"
			+ "Lnet/minecraft/client/util/math/MatrixStack;"
			+ "Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;"
			+ "Lnet/minecraft/client/render/state/CameraRenderState;)V", at = @At("TAIL"))
	private void spogtiers$submitAboveLabel(PlayerEntityRenderState state, MatrixStack matrices,
			OrderedRenderCommandQueue queue, CameraRenderState camera, CallbackInfo ci) {
		AboveLabelRenderer.submit(state, matrices, queue, camera);
	}
}
