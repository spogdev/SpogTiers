package com.spog.tiers.client;

import com.mojang.authlib.GameProfile;
import com.spog.tiers.SpogTiersClient;
import com.spog.tiers.config.SpogTiersConfig;
import com.spog.tiers.util.TagRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

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

	public static Component decorate(Component message) {
		SpogTiersConfig config = SpogTiersClient.config();
		if (config == null || !config.enabled || !config.showInChat || message == null) {
			return message;
		}

		Minecraft client = Minecraft.getInstance();
		if (client.getConnection() == null) {
			return message;
		}

		String flat = message.getString();
		for (PlayerInfo info : client.getConnection().getOnlinePlayers()) {
			GameProfile profile = info.getProfile();
			String name = profile.name();
			if (name == null || name.isEmpty() || !flat.contains(name)) {
				continue;
			}

			Component badge = TagRenderer.badgeFor(profile.id());
			if (badge == null) {
				return message;
			}

			MutableComponent out = Component.empty();
			out.append(badge).append(Component.literal(" "));
			out.append(message);
			return out;
		}
		return message;
	}
}
