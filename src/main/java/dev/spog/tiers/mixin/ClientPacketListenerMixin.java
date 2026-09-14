package dev.spog.tiers.mixin;

import dev.spog.tiers.client.ClientCommands;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts outgoing commands so client-side ones never reach the server.
 *
 * <p>Fabric API's command module would normally provide this, but on 26.x its
 * {@code FabricClientCommandSource} is compiled against intermediary names our
 * identity mappings cannot resolve (see PORTING.md), so we hook vanilla.
 */
@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {
	@Inject(method = "sendCommand", at = @At("HEAD"), cancellable = true)
	private void spogtiers$interceptCommand(String command, CallbackInfo ci) {
		if (ClientCommands.handle(command)) {
			ci.cancel();
		}
	}
}
