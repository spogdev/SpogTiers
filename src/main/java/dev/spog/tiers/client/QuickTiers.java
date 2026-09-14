package dev.spog.tiers.client;

import dev.spog.tiers.SpogTiers;
import dev.spog.tiers.client.gui.ProfileScreen;
import net.minecraft.util.Formatting;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;

import net.minecraft.client.util.InputUtil;

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
	private static final KeyBinding.Category CATEGORY = KeyBinding.Category.create(
			Identifier.of(SpogTiers.MOD_ID, "main"));

	private static final KeyBinding BINDING = new KeyBinding(
			"key.spogtiers.quick_tiers",
			InputUtil.Type.KEYSYM,
			// Unbound by default: the user picks a key in Controls.
			InputUtil.UNKNOWN_KEY.getCode(),
			CATEGORY);

	private QuickTiers() {
	}

	public static KeyBinding binding() {
		return BINDING;
	}

	/** Called each client tick; consumes queued presses. */
	public static void tick(MinecraftClient client) {
		boolean pressed = false;
		while (BINDING.wasPressed()) {
			pressed = true;
		}
		if (!pressed || client.player == null || client.world == null) {
			return;
		}
		// A screen owns the press. The viewer closes itself on this key -- see
		// ProfileScreen.keyPressed -- because vanilla stops feeding presses to
		// key bindings entirely while any screen is open.
		if (client.currentScreen != null) {
			return;
		}

		PlayerEntity target = findTarget(client.player);
		if (target == null) {
			ClientCommands.feedback(Text.literal("No player in sight")
					.formatted(Formatting.GRAY));
			return;
		}
		client.setScreen(new ProfileScreen(target.getGameProfile()));
	}

	/**
	 * The visible player closest to the crosshair.
	 *
	 * <p>Picks by angle rather than by distance, so a distant player you are
	 * looking straight at beats one standing beside you.
	 */
	private static PlayerEntity findTarget(ClientPlayerEntity self) {
		Vec3d eye = self.getEyePos();
		Vec3d look = self.getRotationVec(1.0f).normalize();

		PlayerEntity best = null;
		double bestAim = MIN_AIM;

		for (PlayerEntity candidate : self.getEntityWorld().getPlayers()) {
			if (candidate == self || candidate.isSpectator()) {
				continue;
			}
			// You are not supposed to know who this is.
			if (candidate.isInvisibleTo(self)) {
				continue;
			}

			Vec3d toTarget = candidate.getEyePos().subtract(eye);
			double distance = toTarget.length();
			if (distance > RANGE || distance < 1.0E-4) {
				continue;
			}

			double aim = look.dotProduct(toTarget.multiply(1.0 / distance));
			if (aim <= bestAim) {
				continue;
			}
			// Cheapest checks first; the raycast is the expensive one.
			if (!self.canSee(candidate)) {
				continue;
			}

			bestAim = aim;
			best = candidate;
		}
		return best;
	}
}
