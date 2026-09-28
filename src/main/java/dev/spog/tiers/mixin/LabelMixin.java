package dev.spog.tiers.mixin;

import dev.spog.tiers.client.render.AboveLabelRenderer;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the extra rows after a mob's nameplate.
 *
 * <p>The drawing itself is {@link AboveLabelRenderer}; this only forwards to
 * it. {@link PlayerLabelMixin} does the same for players, which need their own
 * mixin: {@code EntityRenderer} is generic in its render state, so its
 * {@code renderLabelIfPresent} erases to {@code EntityRenderState} while
 * {@code PlayerEntityRenderer} overrides it with the narrowed
 * {@code PlayerEntityRenderState}. One mixin cannot declare both descriptors,
 * and a single handler taking the erased type is rejected against the override
 * -- which is what broke this: nothing drew on 1.21.11 at all, because
 * {@code required} turns a failed injection into a crash.
 *
 * <p>The player renderer does not call {@code super}, it reimplements the
 * label, so targeting the base class alone never fires for players.
 */
@Mixin(EntityRenderer.class)
public class LabelMixin {
	@Inject(method = "renderLabelIfPresent(Lnet/minecraft/client/render/entity/state/EntityRenderState;"
			+ "Lnet/minecraft/client/util/math/MatrixStack;"
			+ "Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;"
			+ "Lnet/minecraft/client/render/state/CameraRenderState;)V", at = @At("TAIL"))
	private void spogtiers$submitAboveLabel(EntityRenderState state, MatrixStack matrices,
			OrderedRenderCommandQueue queue, CameraRenderState camera, CallbackInfo ci) {
		AboveLabelRenderer.submit(state, matrices, queue, camera);
	}
}
