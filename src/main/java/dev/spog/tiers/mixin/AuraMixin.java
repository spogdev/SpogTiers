package dev.spog.tiers.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.spog.tiers.SpogTiersClient;
import dev.spog.tiers.client.WorldAura;
import dev.spog.tiers.util.AuraTarget;
import dev.spog.tiers.data.PlayerGrade;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
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

	/** How far the glow, and the halo beyond it, reach past the ember's body. */
	private static final float GLOW = PIXEL;
	private static final float HALO = 2.0f * PIXEL;

	/**
	 * How much of the ember's body the lit core takes.
	 *
	 * <p>Under half, so the core is a bright point sitting inside the glow
	 * rather than the whole ember: a glow needs something to be glowing around
	 * to read as one.
	 */
	private static final float CORE_SHARE = 0.45f;

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

	/**
	 * How bright each face of a cube is drawn, top to bottom. Not lighting --
	 * the embers are emissive -- just enough difference between faces for a
	 * cube to read as a cube instead of a flat spot.
	 */
	private static final float TOP = 1.0f;
	private static final float SIDE = 0.86f;
	private static final float BOTTOM = 0.7f;

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

			// Tail first, furthest step first, so each nearer part lands on
			// top and the core lands over everything. Each step is a smaller,
			// fainter core where the ember was a moment ago, so the tail bends
			// along the path it took.
			for (int segment = WorldAura.TAIL_SEGMENTS - 1; segment >= 0; segment--) {
				if (!AURA.tailVisible(i, elapsed, segment)) {
					continue;
				}
				float then = AURA.tailTime(elapsed, segment);
				if (checkBlocks && buried(level, state, i, then, height, reach)) {
					continue;
				}
				int colour = AURA.tailColour(rgb, i, elapsed, segment);
				float half = size * AURA.tailSize(segment) / 2.0f;
				poseStack.pushPose();
				place(poseStack, i, then, height, reach);
				cube(poseStack, collector, colour, half);
				poseStack.popPose();
			}

			// Halo, then glow, then the core, nested: the outer faces of a
			// bigger cube sit outside the smaller one's, so drawn largest
			// first the brightest ends up innermost and shows through both.
			int halo = AURA.haloColour(rgb, i, elapsed);
			int glow = AURA.glowColour(rgb, i, elapsed);
			int core = AURA.coreColour(rgb, i, elapsed);
			float half = size / 2.0f;
			// The lit centre, small enough that the glow reads as light coming
			// off it rather than as a second edge around the same square.
			float coreHalf = Math.max(half * CORE_SHARE, PIXEL / 2.0f);
			poseStack.pushPose();
			place(poseStack, i, elapsed, height, reach);
			cube(poseStack, collector, halo, half + HALO);
			cube(poseStack, collector, glow, half + GLOW);
			cube(poseStack, collector, glow, half);
			cube(poseStack, collector, core, coreHalf);
			poseStack.popPose();
		}
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
	 * Moves the pose to the ember's position at {@code at} seconds, turned so
	 * one face looks straight out from the player's axis: the cubes then sit
	 * square to the ring they are on rather than all square to the world.
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
		// The +z face, turned about the vertical by a quarter less the angle,
		// looks along (cos theta, 0, sin theta): outward.
		poseStack.rotate(Axis.YP, Mth.HALF_PI - theta);
	}

	/**
	 * One cube centred on the pose, {@code half} to each face.
	 *
	 * <p>26.3 removed the text-background render types, so each face is drawn
	 * through {@link net.minecraft.client.renderer.SubmitNodeCollector#submitTextBackground}
	 * instead of as hand-built geometry. That call draws a flat quad in the
	 * pose's own XY plane, so a face is placed by rotating the pose to face
	 * along its outward normal and pushing it out by {@code half}. Vanilla
	 * builds its nameplate from the same quad, so the blend, depth and lightmap
	 * behaviour is the plate's -- which is what the old render type gave us.
	 */
	private static void cube(PoseStack poseStack, SubmitNodeCollector collector,
			int colour, float half) {
		int top = shade(colour, TOP);
		int side = shade(colour, SIDE);
		int bottom = shade(colour, BOTTOM);
		// Yaw then pitch to aim +z along each outward normal. The quad is square
		// and centred, so its own spin about that normal does not matter.
		face(poseStack, collector, side, half, 0.0f, 0.0f);            // +z
		face(poseStack, collector, side, half, Mth.PI, 0.0f);          // -z
		face(poseStack, collector, side, half, Mth.HALF_PI, 0.0f);     // +x
		face(poseStack, collector, side, half, -Mth.HALF_PI, 0.0f);    // -x
		face(poseStack, collector, top, half, 0.0f, -Mth.HALF_PI);     // +y
		face(poseStack, collector, bottom, half, 0.0f, Mth.HALF_PI);   // -y
	}

	/**
	 * One square face of half-extent {@code half}, turned to look along the
	 * normal given by {@code yaw} and {@code pitch} and pushed out to the
	 * cube's surface.
	 */
	private static void face(PoseStack poseStack, SubmitNodeCollector collector,
			int colour, float half, float yaw, float pitch) {
		poseStack.pushPose();
		poseStack.rotate(Axis.YP, yaw);
		poseStack.rotate(Axis.XP, pitch);
		poseStack.translate(0.0f, 0.0f, half);
		collector.submitTextBackground(poseStack, -half, -half, half, half, colour,
				Font.DisplayMode.NORMAL, LIGHT);
		poseStack.popPose();
	}

	/** The colour with its red, green and blue scaled by {@code by}; alpha untouched. */
	private static int shade(int argb, float by) {
		int r = (int) (((argb >> 16) & 0xFF) * by);
		int g = (int) (((argb >> 8) & 0xFF) * by);
		int b = (int) ((argb & 0xFF) * by);
		return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
	}
}
