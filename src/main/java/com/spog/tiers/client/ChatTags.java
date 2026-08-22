package com.spog.tiers.client;

import com.mojang.authlib.GameProfile;
import com.spog.tiers.SpogTiersClient;
import com.spog.tiers.config.SpogTiersConfig;
import com.spog.tiers.util.TagRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
import net.minecraft.text.MutableText;

/**
 * Prefixes chat lines with the sender's tier tag.
 *
 * <p>The chat packet carries a rendered line rather than a sender id, so the
 * sender is identified by looking for an online player's name inside it. That
 * is a heuristic: a server with heavy chat formatting may not match, in which
 * case the line is left exactly as it was.
 */
public final class ChatTags {
	private ChatTags() {
	}

	public static Text decorate(Text message) {
		SpogTiersConfig config = SpogTiersClient.config();
		if (config == null || !config.enabled || !config.showInChat || message == null) {
			return message;
		}

		MinecraftClient client = MinecraftClient.getInstance();
		if (client.getNetworkHandler() == null) {
			return message;
		}

		String flat = message.getString();
		for (PlayerListEntry info : client.getNetworkHandler().getPlayerList()) {
			GameProfile profile = info.getProfile();
			String name = profile.name();
			if (name == null || name.isEmpty() || !flat.contains(name)) {
				continue;
			}

			Text badge = TagRenderer.badgeFor(profile.id());
			if (badge == null) {
				return message;
			}

			MutableText out = Text.empty();
			out.append(badge).append(Text.literal(" "));
			out.append(message);
			return out;
		}
		return message;
	}
}
