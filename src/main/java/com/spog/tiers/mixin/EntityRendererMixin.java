package com.spog.tiers.mixin;

import com.spog.tiers.SpogTiersClient;
import com.spog.tiers.util.AboveLabel;
import com.spog.tiers.util.NameShift;
import com.spog.tiers.util.RowPadding;
import com.spog.tiers.util.AuraTarget;
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
		if (SpogTiersClient.config() == null || !(entity instanceof PlayerEntity player)) {
			return;
		}

		// Parked for AuraMixin before the nametag work, and outside its guards:
		// the aura is its own feature, so it must not depend on nametags being
		// switched on or on this player having a nameplate at all.
		AuraTarget.set(state, SpogTiersClient.service() == null
				? null : SpogTiersClient.service().grade(player.getUuid()));

		if (!SpogTiersClient.config().showNametags || state.displayName == null) {
			return;
		}
		state.displayName = TagRenderer.withTag(player.getUuid(), state.displayName);
		// Stashed rather than folded into the name: this version has no second
		// label line of its own, so LabelMixin submits one, and a newline in
		// the name would share the name's single background.
		// Split from the bare name, before it is replaced: passing the tagged
		// name back in would tag it a second time and measure the wrong halves.
		Text bare = state.displayName;
		Text[] around = TagRenderer.aroundName(player.getUuid(), bare);
		NameShift.set(state, around == null ? null : around[0],
				around == null ? null : around[1]);
		Text above = TagRenderer.aboveTag(player.getUuid());
		Text below = TagRenderer.belowTag(player.getUuid());
		// The plate is padded here rather than in LabelMixin, which only draws
		// the two extra rows: the plate is vanilla's own and is the widest of
		// the three more often than not, but a long row above it still left it
		// narrower than the rows either side. NameShift is already set from the
		// unpadded halves above, so centring is measured on the real text.
		state.displayName = RowPadding.plate(state.displayName, above, below);
		AboveLabel.set(state, above);
		AboveLabel.setBelow(state, below);
	}
}
