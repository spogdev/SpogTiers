package com.spog.tiers.mixin;

import com.spog.tiers.client.ClientCommands;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts outgoing commands so client-side ones never reach the server.
 *
 * <p>Fabric API's command module would normally provide this, but hooking
 * vanilla keeps the two version branches on the same approach.
 */
@Mixin(ClientPlayNetworkHandler.class)
public class ClientPlayNetworkHandlerMixin {
	@Inject(method = "sendChatCommand", at = @At("HEAD"), cancellable = true)
	private void spogtiers$interceptCommand(String command, CallbackInfo ci) {
		if (ClientCommands.handle(command)) {
			ci.cancel();
		}
	}
}
