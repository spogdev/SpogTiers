package dev.spog.tiers.client;

import com.mojang.authlib.GameProfile;
import dev.spog.tiers.SpogTiersClient;
import dev.spog.tiers.config.SpogTiersConfig;
import dev.spog.tiers.util.TagRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;

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

			// Auto Adjust Lines deals the rows either side of the name, which
			// is what chat wants too: it has one line, so a layout built as
			// three rows otherwise arrives as a single badge and loses the
			// rest. Without the setting it stays one badge in front.
			//
			// Tried rather than committed to: if the name cannot be found in
			// the tree -- a heavily formatted line, a translated one -- insert
			// returns the message untouched, and returning that would drop the
			// tag that the single badge could still have placed.
			Component[] sides = TagRenderer.balancedAround(profile.id());
			if (sides != null) {
				Component balanced = insert(message, name, sides[0], sides[1]);
				if (balanced != message) {
					return balanced;
				}
			}
			Component badge = TagRenderer.badgeFor(profile.id());
			if (badge == null) {
				return message;
			}
			return insert(message, name, badge, null);
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
	private static Component insert(Component message, String name,
			Component before, Component after) {
		MutableComponent out = Component.empty();
		if (walk(message, name, before, after, out, new boolean[1])) {
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
	private static boolean walk(Component source, String name, Component before,
			Component after, MutableComponent out, boolean[] done) {
		// A translated line -- vanilla's own chat.type.text, which is what an
		// unmodified server sends -- keeps the name in an argument rather than
		// in its own text, so reading only PlainTextContents found nothing and
		// the line went through untouched. The arguments are walked too, and
		// the result is rebuilt from them in order.
		if (source.getContents() instanceof TranslatableContents translatable) {
			return translated(translatable, source, name, before, after, out, done);
		}
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
			if (before != null) {
				out.append(before).append(Component.literal(" "));
			}
			// The name itself, then whatever follows it -- inserted between the
			// name and the rest of the run rather than after the whole run, or
			// it would land past the server's closing bracket.
			out.append(Component.literal(name).setStyle(source.getStyle()));
			if (after != null) {
				out.append(Component.literal(" ")).append(after);
			}
			String rest = own.substring(at + name.length());
			if (!rest.isEmpty()) {
				out.append(Component.literal(rest).setStyle(source.getStyle()));
			}
		}

		for (Component child : source.getSiblings()) {
			walk(child, name, before, after, out, done);
		}
		return done[0];
	}

	/**
	 * A translated line, with the name found inside one of its arguments.
	 *
	 * <p>Rebuilt as the same translation with the arguments replaced, so the
	 * server's own wording and ordering survive: the badge lands inside the
	 * argument that holds the name, which is inside whatever the format wrapped
	 * it in.
	 */
	private static boolean translated(TranslatableContents translatable, Component source,
			String name, Component before, Component after, MutableComponent out,
			boolean[] done) {
		Object[] args = translatable.getArgs();
		Object[] replaced = new Object[args.length];
		for (int i = 0; i < args.length; i++) {
			if (args[i] instanceof Component argument && !done[0]) {
				MutableComponent rebuilt = Component.empty();
				walk(argument, name, before, after, rebuilt, done);
				// Taken only when this argument is the one that placed it:
				// walk reports the shared flag, which an earlier argument may
				// already have set, and rebuilding an untouched argument would
				// flatten the styling the server gave it.
				if (done[0]) {
					replaced[i] = rebuilt;
					continue;
				}
			}
			replaced[i] = args[i];
		}
		if (!done[0]) {
			return false;
		}
		out.append(Component.translatable(translatable.getKey(), replaced)
				.setStyle(source.getStyle()));
		for (Component child : source.getSiblings()) {
			walk(child, name, before, after, out, done);
		}
		return true;
	}

}
