package dev.spog.tiers.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.spog.tiers.SpogTiersClient;
import dev.spog.tiers.client.TrialSparks;
import dev.spog.tiers.client.WorldAura;
import dev.spog.tiers.data.PlayerGrade;
import dev.spog.tiers.util.AuraTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the tier aura around a player in the world.
 *
 * <p>The same effect the profile screen puts around the skin model, and the
 * same embers: {@link WorldAura} owns the motion and the colours of each
 * ember's parts, and both callers read it, so the two cannot drift apart.
 * Only the units differ -- the profile lays the embers out over its panel in
 * GUI pixels, this one over the player's own bounding box in blocks.
 *
 * <p>Drawn as small cubes rather than spawned as particles. Vanilla's
 * particles carry their own texture, their own physics and their own fade, so
 * they look like an effect the game already has; the aura is a specific look,
 * and reproducing it means drawing it. A cube, unlike a sprite, is the same
 * solid thing from every angle: it needs no turning to the camera, and it
 * reads as something standing in the world round the player rather than
 * pinned to the screen. The faces use the same untextured translucent layer
 * the nameplate backdrop uses, so they depth-test against the world like
 * anything else.
 */
@Mixin(EntityRenderer.class)
public class AuraMixin {
	/** One shared layout: the embers are a pure function of time, not state. */
	private static final WorldAura AURA = new WorldAura();

	/**
	 * One profile-screen pixel in blocks. The skin widget shows a player
	 * 2.125 blocks tall in 170 pixels, and every size below is the profile's
	 * pixel count times this, so an ember here is the size it is there.
	 */
	private static final float PIXEL = 2.125f / 170.0f;

	/** Core size in blocks: the profile's one to four pixels. */
	private static final float MIN_SIZE = PIXEL;
	private static final float MAX_SIZE = 4.0f * PIXEL;

	/**
	 * How long a streak is against the mote size the aura hands out.
	 *
	 * <p>The sprite is a tall thin column and the aura's sizes were chosen for
	 * a cube, so without this a spark would be barely a speck.
	 *
	 * <p>Worked back from the size wanted rather than guessed: the longest
	 * streak is {@code MAX_SIZE * TrialSparks.QUAD_SIZE * STREAK}, so 11 puts
	 * it at 0.41 blocks against a player's 1.8. An earlier 44 came from
	 * mis-estimating MAX_SIZE and drew streaks 1.65 blocks long -- slabs
	 * nearly as tall as the player, which is what they looked like.
	 */
	private static final float STREAK = 11.0f;

	/** How wide the aura is at its widest, as a multiple of the body width. */
	private static final float SPREAD = 1.7f;

	/** Below this alpha an ember is not worth a draw call. */
	private static final float MIN_ALPHA = 0.01f;

	/**
	 * A player's height standing, in blocks, used in place of their real one
	 * while they crouch.
	 *
	 * <p>Crouching is a quick, repeated action, and scaling the aura to the
	 * shorter box made the whole column jump down and squash every time --
	 * far more distracting than the aura simply standing still. Swimming and
	 * crawling are held rather than flicked in and out of, so those keep
	 * their real height and the aura lies down with the player.
	 */
	private static final float STANDING_HEIGHT = 1.8f;

	/** Full-bright lightmap coordinates: embers glow, they are not lit. */
	private static final int LIGHT = 0xF000F0;

	// On submit() rather than the nameplate's own method, so the aura is not
	// tied to the plate: vanilla hides the plate out of range, while sneaking,
	// with F1, and for a player with no name to show, and the aura should
	// outlast all of that. submit() runs for every entity that renders at all.
	//
	// The pose here is the entity's own origin, unrotated and unscaled: the
	// subclasses that push a pose of their own do it around their model and
	// pass this same stack up, which is why the nameplate, built from it too,
	// lands upright above the head however the player is turned.
	@Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;"
			+ "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;"
			+ "Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
			at = @At("HEAD"))
	private void spogtiers$submitAura(EntityRenderState state, PoseStack poseStack,
			SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo ci) {
		var config = SpogTiersClient.config();
		// Its own switch, and deliberately not the tagger's: turning tags off
		// is about what is written over a player, and the aura is not writing
		// anything. Someone who wants the decoration without the tags gets it.
		if (config == null || !config.extraTierlists || !config.showParticles) {
			return;
		}
		PlayerGrade grade = AuraTarget.get(state);
		if (grade == null || !grade.isGraded()) {
			return;
		}
		// Nothing to decorate: a player you cannot see should not be given
		// away by their own aura.
		if (state.isInvisible) {
			return;
		}

		// The player's own box, so the aura fits whoever it is around, except
		// while crouching: see STANDING_HEIGHT.
		float height = auraHeight(state);
		float width = state.boundingBoxWidth;
		if (height <= 0.0f || width <= 0.0f) {
			return;
		}

		// Seconds, the same unit the profile aura counts in, so both read the
		// same moment of the same animation. Wrapped well short of float's
		// precision limit so the motion stays smooth on a long-lived world.
		Minecraft minecraft = Minecraft.getInstance();
		float elapsed = (minecraft.level.getGameTime() % 100000L
				+ minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false)) / 20.0f;
		int rgb = grade.foreground() & 0xFFFFFF;
		float reach = SPREAD * width / 2.0f;
		// Sneaking into a gap puts part of the aura inside the blocks around
		// the player, where an ember would show through the far face of a
		// wall. Depth testing cannot help -- the ember is nearer than the
		// face it emerges from -- so embers inside a block are dropped
		// instead. Only while sneaking: the check costs a block lookup each,
		// and standing upright the aura is in open air.
		Level level = minecraft.level;
		boolean checkBlocks = state.isDiscrete;

		// Every part is posed here, at submit time: the geometry callbacks run
		// later, during the frame, when this stack is long gone, so nothing
		// inside them may touch it.
		for (int i = 0; i < WorldAura.MOTES; i++) {
			if (AURA.alpha(i, elapsed) <= MIN_ALPHA) {
				continue;
			}
			if (checkBlocks && buried(level, state, i, elapsed, height, reach)) {
				continue;
			}
			float size = MIN_SIZE + AURA.size(i, elapsed) * (MAX_SIZE - MIN_SIZE);
			float life = AURA.life(i, elapsed);

			int colour = TrialSparks.colour(rgb, life, AURA.alpha(i, elapsed));
			spark(poseStack, collector, camera, colour, life, size, i, elapsed, height, reach);
		}
	}

	/**
	 * One streak, billboarded and posed at the mote's place.
	 *
	 * <p>Sized the way vanilla sizes this particle: its own quad size times
	 * the growth curve, so a spark snaps to full length as it is struck. The
	 * sprite is a thin column, so the quad is drawn to the same proportion
	 * rather than square -- a square would stretch the streak sideways.
	 */
	private static void spark(PoseStack poseStack, SubmitNodeCollector collector,
			CameraRenderState camera, int colour, float life, float size,
			int mote, float at, float height, float reach) {
		float tall = size * TrialSparks.QUAD_SIZE * TrialSparks.growth(life) * STREAK;
		if (tall <= 0.0f) {
			return;
		}
		float wide = tall * TrialSparks.ASPECT;
		poseStack.pushPose();
		place(poseStack, mote, at, height, reach);
		// LOOKAT_Y, as the particle uses: turned to the camera about the
		// vertical only, so the streak stays upright however it is viewed
		// instead of tipping with the pitch.
		poseStack.rotate(Axis.YP, -camera.yRot * Mth.DEG_TO_RAD);
		quad(poseStack, collector, TrialSparks.sprite(life), colour, wide, tall,
				TrialSparks.spriteHeight(life), false);
		// The white head over it. Translucent, because its whole job is to
		// fade out down the streak, and white rather than tinted so it burns
		// out the colour at the top instead of deepening it.
		quad(poseStack, collector, TrialSparks.hotSprite(life), 0xFFFFFFFF, wide, tall,
				TrialSparks.spriteHeight(life), true);
		poseStack.popPose();
	}

	/**
	 * A camera-facing textured quad, {@code wide} by {@code tall}, centred on
	 * the pose.
	 *
	 * <p>Drawn as a cutout: every pixel of the streak is opaque, and the
	 * transparent margin around it is discarded rather than blended. Nothing
	 * behind a spark shows through it, so the colour on screen is the grade's
	 * own rather than a mix of it and the ground -- which is what an additive
	 * or translucent draw gave, each in its own way.
	 *
	 * <p>The lightmap is full-bright, so the pipeline's per-face lighting
	 * samples maximum light and leaves the colour alone: a spark glows rather
	 * than being lit.
	 *
	 * <p>Wound bottom-left, bottom-right, top-right, top-left. The pose here is
	 * y-up and the pipeline culls back faces, so the other order draws nothing
	 * at all and says nothing about why -- see the aura's own history.
	 */
	private static void quad(PoseStack poseStack, SubmitNodeCollector collector,
			Identifier sprite, int colour, float wide, float tall, int length,
			boolean blend) {
		float x = wide / 2.0f;
		float y = tall / 2.0f;
		// Only the streak's own corner of the sheet. The sprite is drawn on an
		// 8x8 square -- a power of two, which the GUI loads without the blank
		// frame a 1-pixel-wide texture cost it -- so the rest is margin, and
		// spanning the whole thing would shrink the streak into a sliver of
		// its own quad.
		float u0 = TrialSparks.STREAK_LEFT / (float) TrialSparks.SPRITE_SIZE;
		float u1 = (TrialSparks.STREAK_LEFT + TrialSparks.STREAK_WIDTH)
				/ (float) TrialSparks.SPRITE_SIZE;
		float v0 = TrialSparks.STREAK_TOP / (float) TrialSparks.SPRITE_SIZE;
		float v1 = (TrialSparks.STREAK_TOP + length) / (float) TrialSparks.SPRITE_SIZE;
		collector.submitCustomGeometry(poseStack,
				blend ? RenderTypes.entityTranslucentEmissive(sprite)
						: RenderTypes.entityCutout(sprite), (pose, buffer) -> {
					vertex(buffer, pose, -x, -y, colour, u0, v1);
					vertex(buffer, pose, x, -y, colour, u1, v1);
					vertex(buffer, pose, x, y, colour, u1, v0);
					vertex(buffer, pose, -x, y, colour, u0, v0);
				});
	}

	/** One corner, with everything the entity format wants. */
	private static void vertex(VertexConsumer buffer, PoseStack.Pose pose,
			float x, float y, int colour, float u, float v) {
		buffer.addVertex(pose, x, y, 0.0f)
				.setColor(colour)
				.setUv(u, v)
				.setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(LIGHT)
				.setNormal(pose, 0.0f, 0.0f, 1.0f);
	}

	/**
	 * How tall to draw the aura: the entity's own box, but a crouching
	 * player's standing height, so the column neither squashes nor drops as
	 * they duck.
	 */
	private static float auraHeight(EntityRenderState state) {
		if (state instanceof LivingEntityRenderState living && living.pose == Pose.CROUCHING) {
			return Math.max(state.boundingBoxHeight, STANDING_HEIGHT);
		}
		return state.boundingBoxHeight;
	}

	/**
	 * Whether the ember at {@code at} seconds sits inside a block that would
	 * be drawn solid, so it must not be drawn.
	 *
	 * <p>Worked in world coordinates, which the render state carries
	 * alongside the entity-local ones the pose is built from.
	 */
	private static boolean buried(Level level, EntityRenderState state, int mote, float at,
			float height, float reach) {
		float theta = AURA.theta(mote, at);
		float radius = AURA.radius(mote, at) * reach;
		BlockPos pos = BlockPos.containing(
				state.x + Mth.cos(theta) * radius,
				state.y + AURA.up(mote, at) * height,
				state.z + Mth.sin(theta) * radius);
		return level.getBlockState(pos).isSolidRender();
	}

	/**
	 * Moves the pose to the ember's position at {@code at} seconds.
	 *
	 * <p>Position only: the streak is turned to the camera by its caller, so
	 * any turn applied here would be undone a moment later.
	 *
	 * <p>Measured from the pose's own origin, which is the entity's: its feet.
	 * The nameplate's attachment point would have served as well, but only
	 * while there is a plate to read it from -- vanilla leaves it null on the
	 * frames it hides one.
	 */
	private static void place(PoseStack poseStack, int mote, float at,
			float height, float reach) {
		float theta = AURA.theta(mote, at);
		float radius = AURA.radius(mote, at) * reach;
		// Round the player's axis: x across the body, z through it, matching
		// the profile screen's view from the front.
		float x = Mth.cos(theta) * radius;
		float z = Mth.sin(theta) * radius;
		float y = AURA.up(mote, at) * height;
		poseStack.translate(x, y, z);
	}

}
