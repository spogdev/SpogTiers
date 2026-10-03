package dev.spog.tiers.mixin;

import dev.spog.tiers.SpogTiersClient;
import dev.spog.tiers.util.TabSorter;
import dev.spog.tiers.util.TagRenderer;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
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

	/**
	 * Reorders the tab list by the configured sort.
	 *
	 * <p>Hooked on the return of the method that gathers the players, which is
	 * after vanilla has sorted them: the list arrives in the order the game
	 * would have drawn, so a sort left on Server changes nothing and the
	 * spectator grouping is still there to preserve.
	 */
	@Inject(method = "getPlayerInfos", at = @At("RETURN"), cancellable = true)
	private void spogtiers$sortTabList(CallbackInfoReturnable<List<PlayerInfo>> cir) {
		List<PlayerInfo> players = cir.getReturnValue();
		if (players == null) {
			return;
		}
		List<PlayerInfo> sorted = TabSorter.sort(players);
		if (sorted != players) {
			cir.setReturnValue(sorted);
		}
	}
}
