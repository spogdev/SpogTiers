package com.spog.tiers.mixin;

import com.spog.tiers.SpogTiersClient;
import com.spog.tiers.util.TagRenderer;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

/**
 * Prepends the tier badge to names shown in the tab list.
 *
 * <p>Mojmap equivalent of the yarn {@code PlayerListHud#getPlayerName} hook
 * used on 1.21.x branches.
 */
@Mixin(PlayerTabOverlay.class)
public class PlayerTabOverlayMixin {
	@Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
	private void spogtiers$addTierTag(PlayerInfo entry, CallbackInfoReturnable<Component> cir) {
		if (SpogTiersClient.config() == null || !SpogTiersClient.config().showTabList) {
			return;
		}
		UUID uuid = entry.getProfile().id();
		if (uuid == null) {
			return;
		}
		Component tagged = TagRenderer.withTagOnOneLine(uuid, cir.getReturnValue());
		if (tagged != cir.getReturnValue()) {
			cir.setReturnValue(tagged);
		}
	}
}
