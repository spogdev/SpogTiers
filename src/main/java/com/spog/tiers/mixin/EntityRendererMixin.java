package com.spog.tiers.mixin;

import com.spog.tiers.SpogTiersClient;
import com.spog.tiers.util.AboveLabel;
import com.spog.tiers.util.TagRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
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
		Text name = TagRenderer.withTag(player.getUuid(), state.displayName);
		Text above = TagRenderer.aboveTag(player.getUuid());

		// The two lines must be the same width. Each draws its own backdrop
		// centred on its own text, and a narrower line's backdrop ends inside
		// the wider one's, where the overlapping alpha shows as a dark bar at
		// each end. Whichever is narrower is widened to match, to the pixel.
		if (above != null) {
			TextRenderer font = MinecraftClient.getInstance().textRenderer;
			int nameWidth = font.getWidth(name);
			int aboveWidth = font.getWidth(above);
			if (aboveWidth < nameWidth) {
				above = TagRenderer.padTo(above, nameWidth - aboveWidth);
			} else if (nameWidth < aboveWidth) {
				name = TagRenderer.padTo(name, aboveWidth - nameWidth);
			}
		}

		state.displayName = name;
		// Stashed rather than folded into the name: this version has no second
		// label line of its own, so LabelMixin submits one, and a newline in
		// the name would share the name's single background.
		AboveLabel.set(state, above);
	}
}
