package com.spog.tiers.mixin;

import com.spog.tiers.SpogTiersClient;
import com.spog.tiers.util.TagRenderer;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Rewrites the display name carried on the render state so the in-world
 * nametag shows the tier badge. 1.21.5+ builds nametags from the render state
 * rather than the entity, so this is the one place both the label and any
 * downstream consumers read it from.
 */
@Mixin(EntityRenderer.class)
public class EntityRendererMixin {
	@Inject(method = "updateRenderState", at = @At("TAIL"))
	private void spogtiers$tagNameplate(Entity entity, EntityRenderState state, float tickDelta,
			CallbackInfo ci) {
		if (SpogTiersClient.config() == null || !SpogTiersClient.config().showNametags) {
			return;
		}
		if (!(entity instanceof PlayerEntity player) || state.displayName == null) {
			return;
		}
		state.displayName = TagRenderer.withTag(player.getUuid(), state.displayName);
		// Stashed rather than folded into the name: this version has no second
		// label line of its own, so LabelMixin submits one, and a newline in
		// the name would share the name's single background.
		AboveLabel.set(state, TagRenderer.aboveTag(player.getUuid()));
	}
}
