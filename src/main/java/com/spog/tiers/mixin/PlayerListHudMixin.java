package com.spog.tiers.mixin;

import com.spog.tiers.SpogTiersClient;
import com.spog.tiers.util.TagRenderer;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

/** Prepends the tier badge to names shown in the tab list. */
@Mixin(PlayerListHud.class)
public class PlayerListHudMixin {
	@Inject(method = "getPlayerName", at = @At("RETURN"), cancellable = true)
	private void spogtiers$addTierTag(PlayerListEntry entry, CallbackInfoReturnable<Text> cir) {
		if (SpogTiersClient.config() == null || !SpogTiersClient.config().showTabList) {
			return;
		}
		UUID uuid = entry.getProfile().id();
		if (uuid == null) {
			return;
		}
		Text tagged = TagRenderer.withTagOnOneLine(uuid, cir.getReturnValue());
		if (tagged != cir.getReturnValue()) {
			cir.setReturnValue(tagged);
		}
	}
}
