package dev.spog.tiers.client;

import dev.spog.tiers.SpogTiers;
import dev.spog.tiers.client.gui.ProfileScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import com.mojang.blaze3d.platform.InputConstants;

/**
 * Opens the tier profile of whoever you are looking at.
 *
 * <p>Deliberately forgiving about aim and strict about knowledge: the target
 * only has to be near the crosshair, but you must actually be able to see them,
 * so someone invisible or behind a wall is never revealed.
 */
public final class QuickTiers {
	/** How far to look for a player, in blocks. */
	private static final double RANGE = 64.0;
	/**
	 * How closely the target must line up with the crosshair, as the cosine of
	 * the angle between them. 0.94 is about 20 degrees -- "the general
	 * direction" rather than pixel-accurate aim.
	 */
	private static final double MIN_AIM = 0.94;

	/**
	 * Our own heading in the Controls list.
	 *
	 * <p>Under vanilla's Miscellaneous the binding is easy to miss among the
	 * game's own entries; its label comes from {@code key.category.spogtiers.main}.
	 */
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
			Identifier.fromNamespaceAndPath(SpogTiers.MOD_ID, "main"));

	private static final KeyMapping BINDING = new KeyMapping(
			"key.spogtiers.quick_tiers",
			InputConstants.Type.KEYBOARD,
			// Unbound by default: the user picks a key in Controls.
			InputConstants.UNKNOWN.getValue(),
			CATEGORY);

	private QuickTiers() {
	}

	public static KeyMapping binding() {
		return BINDING;
	}

	/** Called each client tick; consumes queued presses. */
	public static void tick(Minecraft client) {
		boolean pressed = false;
		while (BINDING.consumeClick()) {
			pressed = true;
		}
		if (!pressed || client.player == null || client.level == null) {
			return;
		}
		// A screen owns the press. The viewer closes itself on this key -- see
		// ProfileScreen.keyPressed -- because vanilla stops feeding presses to
		// key bindings entirely while any screen is open.
		if (client.gui.screen() != null) {
			return;
		}

		Player target = findTarget(client.player);
		if (target == null) {
			ClientCommands.feedback(Component.literal("No player in sight")
					.withStyle(ChatFormatting.GRAY));
			return;
		}
		client.gui.setScreen(new ProfileScreen(target.getGameProfile()));
	}

	/**
	 * The visible player closest to the crosshair.
	 *
	 * <p>Picks by angle rather than by distance, so a distant player you are
	 * looking straight at beats one standing beside you.
	 */
	private static Player findTarget(LocalPlayer self) {
		Vec3 eye = self.getEyePosition();
		Vec3 look = self.getViewVector(1.0f).normalize();

		Player best = null;
		double bestAim = MIN_AIM;

		for (Player candidate : self.level().players()) {
			if (candidate == self || candidate.isSpectator()) {
				continue;
			}
			// You are not supposed to know who this is.
			if (candidate.isInvisibleTo(self)) {
				continue;
			}

			Vec3 toTarget = candidate.getEyePosition().subtract(eye);
			double distance = toTarget.length();
			if (distance > RANGE || distance < 1.0E-4) {
				continue;
			}

			double aim = look.dot(toTarget.scale(1.0 / distance));
			if (aim <= bestAim) {
				continue;
			}
			// Cheapest checks first; the raycast is the expensive one.
			if (!self.hasLineOfSight(candidate)) {
				continue;
			}

			bestAim = aim;
			best = candidate;
		}
		return best;
	}
}
