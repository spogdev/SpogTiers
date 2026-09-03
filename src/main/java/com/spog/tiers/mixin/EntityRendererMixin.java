package com.spog.tiers.mixin;

import com.spog.tiers.SpogTiersClient;
import com.spog.tiers.util.AboveLabel;
import com.spog.tiers.util.TagRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
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
		if (SpogTiersClient.config() == null || !SpogTiersClient.config().showNametags) {
			return;
		}
		if (!(entity instanceof Player player) || state.nameTag == null) {
			return;
		}
		Component name = TagRenderer.withTag(player.getUUID(), state.nameTag);
		Component above = TagRenderer.aboveTag(player.getUUID());

		// The two lines must be the same width. Each draws its own backdrop
		// centred on its own text, and a narrower line's backdrop ends inside
		// the wider one's, where the overlapping alpha shows as a dark bar at
		// each end. Whichever is narrower is widened to match, to the pixel.
		if (above != null) {
			Font font = Minecraft.getInstance().font;
			int nameWidth = font.width(name);
			int aboveWidth = font.width(above);
			if (aboveWidth < nameWidth) {
				above = TagRenderer.padTo(above, nameWidth - aboveWidth);
			} else if (nameWidth < aboveWidth) {
				name = TagRenderer.padTo(name, aboveWidth - nameWidth);
			}
		}

		state.nameTag = name;
		// Stashed for LabelMixin rather than put in scoreText: vanilla draws
		// that field under the name, since it is the scoreboard line.
		AboveLabel.set(state, above);
	}
}
