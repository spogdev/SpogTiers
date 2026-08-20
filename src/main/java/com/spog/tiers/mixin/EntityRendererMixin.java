package com.spog.tiers.mixin;

import com.spog.tiers.SpogTiersClient;
import com.spog.tiers.util.TagRenderer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Rewrites the name carried on the render state so the in-world nametag shows
 * the tier badge.
 *
 * <p>Mojmap differences from the 1.21.x branches: the method is
 * {@code extractRenderState} (yarn {@code updateRenderState}) and the field is
 * {@code nameTag} (yarn {@code displayName}).
 */
@Mixin(EntityRenderer.class)
public class EntityRendererMixin {
	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void spogtiers$tagNameplate(Entity entity, EntityRenderState state, float partialTick, CallbackInfo ci) {
		if (SpogTiersClient.config() == null || !SpogTiersClient.config().showNametags) {
			return;
		}
		if (!(entity instanceof Player player) || state.nameTag == null) {
			return;
		}
		state.nameTag = TagRenderer.withTag(player.getUUID(), state.nameTag);
	}
}
