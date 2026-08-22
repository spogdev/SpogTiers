package com.spog.tiers.mixin;

import com.spog.tiers.client.ChatTags;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.network.message.MessageSignatureData;
import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Prefixes incoming chat lines with the sender's tier tag. */
@Mixin(ChatHud.class)
public class ChatHudMixin {
	@ModifyVariable(method = "addMessage(Lnet/minecraft/text/Text;"
			+ "Lnet/minecraft/network/message/MessageSignatureData;"
			+ "Lnet/minecraft/client/gui/hud/MessageIndicator;)V",
			at = @At("HEAD"), argsOnly = true, index = 1)
	private Text spogtiers$decorate(Text message) {
		return ChatTags.decorate(message);
	}
}
