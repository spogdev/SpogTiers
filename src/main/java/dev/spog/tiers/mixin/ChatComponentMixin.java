package dev.spog.tiers.mixin;

import dev.spog.tiers.client.ChatTags;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Prefixes chat messages with the sender's tier tag.
 *
 * <p>The packet does not tell us which part of the rendered line is the name,
 * so {@link ChatTags} matches the message against the players we already know
 * about rather than trying to parse it.
 */
@Mixin(ChatComponent.class)
public class ChatComponentMixin {
	@ModifyVariable(method = "addPlayerMessage", at = @At("HEAD"), argsOnly = true, index = 1)
	private Component spogtiers$tagChat(Component message) {
		return ChatTags.decorate(message);
	}
}
