package com.spog.tiers.mixin;

import com.spog.tiers.SpogTiersClient;
import com.spog.tiers.util.AboveLabel;
import com.spog.tiers.util.NameShift;
import com.spog.tiers.util.AuraTarget;
import com.spog.tiers.util.TagRenderer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
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
		if (SpogTiersClient.config() == null || !(entity instanceof Player player)) {
			return;
		}

		// Parked for AuraMixin before the nametag work, and outside its guards:
		// the aura is its own feature, so it must not depend on nametags being
		// switched on or on this player having a nameplate at all.
		AuraTarget.set(state, SpogTiersClient.service() == null
				? null : SpogTiersClient.service().grade(player.getUUID()));

		if (!SpogTiersClient.config().showNametags || state.nameTag == null) {
			return;
		}
		// Split from the bare name, before it is replaced: passing the tagged
		// name back in would tag it a second time and measure the wrong halves.
		Component bare = state.nameTag;
		Component[] around = TagRenderer.aroundName(player.getUUID(), bare);
		NameShift.set(state, around == null ? null : around[0],
				around == null ? null : around[1]);
		state.nameTag = TagRenderer.withTag(player.getUUID(), bare);
		// Stashed for LabelMixin rather than put in scoreText: vanilla draws
		// that field under the name, since it is the scoreboard line.
		AboveLabel.set(state, TagRenderer.aboveTag(player.getUUID()));
		AboveLabel.setBelow(state, TagRenderer.belowTag(player.getUUID()));
	}
}
