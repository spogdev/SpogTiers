package dev.spog.tiers.mixin;

import com.mojang.brigadier.CommandDispatcher;
import dev.spog.tiers.client.ClientCommands;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds our client-side commands to the dispatcher the client builds from the
 * server's command tree, so they autocomplete and are not shown as unknown
 * while typing.
 *
 * <p>The server rebuilds this tree whenever it resends the commands packet, so
 * the node is re-registered on every {@code handleCommands}.
 */
@Mixin(ClientPacketListener.class)
public class ClientCommandsMixin {
	@Shadow
	private CommandDispatcher<ClientSuggestionProvider> commands;

	@Inject(method = "handleCommands", at = @At("TAIL"))
	private void spogtiers$registerClientCommands(ClientboundCommandsPacket packet, CallbackInfo ci) {
		ClientCommands.register(commands);
	}
}
