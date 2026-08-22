package com.spog.tiers.mixin;

import com.spog.tiers.client.ClientCommands;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.CommandTreeS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.client.network.ClientCommandSource;

/**
 * Adds our commands to the dispatcher the server's command tree builds, so
 * they autocomplete and are not underlined as unknown while typing.
 */
@Mixin(ClientPlayNetworkHandler.class)
public class ClientCommandsMixin {
	@Shadow
	private CommandDispatcher<ClientCommandSource> commandDispatcher;

	@Inject(method = "onCommandTree", at = @At("TAIL"))
	private void spogtiers$registerClientCommands(CommandTreeS2CPacket packet, CallbackInfo ci) {
		ClientCommands.register(commandDispatcher);
	}
}
