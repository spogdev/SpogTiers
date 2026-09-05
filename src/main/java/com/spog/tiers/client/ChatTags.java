package com.spog.tiers.client;

import com.mojang.authlib.GameProfile;
import com.spog.tiers.SpogTiersClient;
import com.spog.tiers.config.SpogTiersConfig;
import com.spog.tiers.util.TagRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.PlainTextContents;

/**
 * Prefixes chat lines with the sender's tier tag.
 *
 * <p>The chat packet carries a rendered line rather than a sender id, so the
 * sender is identified by looking for an online player's name inside it. That
 * is a heuristic: a server with heavy chat formatting may not match, in which
 * case the line is left exactly as it was.
 *
 * <p>The badge goes beside the name rather than in front of the line, so it
 * sits inside whatever the server wrapped the name in -- angle brackets, most
 * often -- instead of ahead of them.
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
			return insert(message, name, badge);
		}
		return message;
	}

	/**
	 * Puts the badge immediately before the sender's name, wherever that name
	 * appears in the line.
	 *
	 * <p>Prefixing the whole line put the tag outside the angle brackets a
	 * server writes round a name, so it read as part of the chat furniture
	 * rather than as part of who was speaking. Walking the tree instead means
	 * the badge lands inside whatever the server wrapped the name in, and the
	 * line's own formatting is left untouched.
	 *
	 * <p>Only the first occurrence is decorated: a player quoting their own
	 * name should not collect a second badge.
	 */
	private static Component insert(Component message, String name, Component badge) {
		MutableComponent out = Component.empty();
		if (walk(message, name, badge, out, new boolean[1])) {
			return out;
		}
		return message;
	}

	/**
	 * Copies {@code source} into {@code out}, splitting the one run that holds
	 * the name so the badge can go in front of it.
	 *
	 * @param done one-element flag, set once the badge has been placed
	 * @return true if anything was copied
	 */
	private static boolean walk(Component source, String name, Component badge,
			MutableComponent out, boolean[] done) {
		String own = source.getContents() instanceof PlainTextContents plain
				? plain.text() : "";
		int at = done[0] ? -1 : own.indexOf(name);

		if (at < 0) {
			// This run does not hold the name, so it is copied as it is --
			// style included, since a server may colour the name itself.
			if (!own.isEmpty()) {
				out.append(Component.literal(own).setStyle(source.getStyle()));
			}
		} else {
			// Split around the name: what came before, the badge, then the
			// name and the rest, all keeping this run's own style.
			done[0] = true;
			if (at > 0) {
				out.append(Component.literal(own.substring(0, at))
						.setStyle(source.getStyle()));
			}
			out.append(badge).append(Component.literal(" "));
			out.append(Component.literal(own.substring(at)).setStyle(source.getStyle()));
		}

		for (Component child : source.getSiblings()) {
			walk(child, name, badge, out, done);
		}
		return done[0];
	}

}
