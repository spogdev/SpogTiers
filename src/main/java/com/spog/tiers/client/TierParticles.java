package com.spog.tiers.client;

import com.spog.tiers.SpogTiersClient;
import com.spog.tiers.data.PlayerGrade;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * Embers drifting up around a player in the world, in the colour of their Door
 * SMP tier -- the in-world counterpart of the aura on the profile screen.
 *
 * <p>Real particles here, rather than the quads the profile screen draws: this
 * is a world entity, so the game's own particle system is available and its
 * particles light, fade and sort correctly against the terrain.
 *
 * <p>Driven from the client tick rather than a renderer, so the effect does not
 * depend on the player being on screen this frame and does not multiply when a
 * player is drawn more than once.
 */
public final class TierParticles {
	/** How many ticks between emissions for one player. */
	private static final int INTERVAL = 2;

	/** Particles released per emission. Low: this repeats every INTERVAL. */
	private static final int PER_EMIT = 2;

	/** How far out from the player's centre the ring of embers sits. */
	private static final double RADIUS = 0.42;

	/** Fraction of the player's height the embers spawn within. */
	private static final double LOW = 0.15;
	private static final double HIGH = 0.95;

	/** Upward drift, in blocks per tick. */
	private static final double RISE = 0.035;

	/** Sideways wander, so the column is not a rigid cylinder. */
	private static final double WANDER = 0.012;

	/**
	 * How far away a player still gets embers, squared.
	 *
	 * <p>Beyond this the particles are a scatter of single pixels and not worth
	 * the draw calls, so the effect simply stops.
	 */
	private static final double RANGE_SQ = 32.0 * 32.0;

	private static int tick;

	private TierParticles() {
	}

	/** Emits for every graded player in view. Call once per client tick. */
	public static void tick(Minecraft client) {
		if (client.level == null || client.isPaused()) {
			return;
		}
		var config = SpogTiersClient.config();
		// The same switch that governs the tag and the profile aura: with our
		// tierlist switched off, nothing of it should show anywhere.
		if (config == null || !config.enabled || !config.extraTierlists) {
			return;
		}
		if (++tick % INTERVAL != 0) {
			return;
		}

		Vec3 camera = client.gameRenderer.getMainCamera().position();
		List<? extends Player> players = client.level.players();
		for (Player player : players) {
			if (player.isInvisible() || player.isSpectator()) {
				continue;
			}
			if (player.position().distanceToSqr(camera) > RANGE_SQ) {
				continue;
			}
			PlayerGrade grade = gradeOf(player.getUUID());
			if (grade == null || !grade.isGraded()) {
				continue;
			}
			emit(client, player, grade.foreground() & 0xFFFFFF);
		}
	}

	/**
	 * The player's tier, or null.
	 *
	 * <p>Never blocks and never starts a fetch of its own: it reads what the
	 * service already holds, so an unknown player simply has no embers until
	 * something else looks them up.
	 */
	private static PlayerGrade gradeOf(UUID uuid) {
		var service = SpogTiersClient.service();
		return service == null ? null : service.grade(uuid);
	}

	private static void emit(Minecraft client, Player player, int rgb) {
		var random = player.getRandom();
		double height = player.getBbHeight();
		float red = ((rgb >> 16) & 0xFF) / 255.0f;
		float green = ((rgb >> 8) & 0xFF) / 255.0f;
		float blue = (rgb & 0xFF) / 255.0f;
		ColorParticleOption option =
				ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, red, green, blue);

		for (int i = 0; i < PER_EMIT; i++) {
			// Around the player rather than inside them, so the embers read as
			// surrounding the skin instead of clipping through it.
			double angle = random.nextDouble() * Mth.TWO_PI;
			double radius = RADIUS * (0.75 + random.nextDouble() * 0.35);
			double x = player.getX() + Math.cos(angle) * radius;
			double z = player.getZ() + Math.sin(angle) * radius;
			double y = player.getY() + height * (LOW + random.nextDouble() * (HIGH - LOW));

			client.level.addParticle(option, x, y, z,
					(random.nextDouble() - 0.5) * WANDER,
					RISE,
					(random.nextDouble() - 0.5) * WANDER);
		}
	}
}
