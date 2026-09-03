package com.spog.tiers.mixin;

import com.spog.tiers.SpogTiersClient;
import com.spog.tiers.client.WorldAura;
import com.spog.tiers.data.PlayerGrade;
import com.spog.tiers.util.AuraTarget;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the tier aura around a player in the world.
 *
 * <p>The same effect the profile screen puts around the skin model, and the
 * same motion: {@link WorldAura} owns the maths and both callers read it, so
 * the two cannot drift apart. Only the units differ -- the profile lays the
 * motes out over its panel in GUI pixels, this one over the player's own
 * bounding box in blocks.
 *
 * <p>Drawn as camera-facing quads rather than spawned as particles. Vanilla's
 * particles carry their own texture, their own physics and their own fade, so
 * they look like an effect the game already has; the aura is a specific look,
 * and reproducing it means drawing it. The quads use the same untextured
 * translucent layer the nameplate backdrop uses, so they light and depth-test
 * against the world like anything else.
 */
@Mixin(EntityRenderer.class)
public class AuraMixin {
	/** One shared layout: the motes are a pure function of time, not state. */
	private static final WorldAura AURA = new WorldAura();

	/** Mote size in blocks, at the largest. */
	private static final float MAX_SIZE = 0.085f;
	private static final float MIN_SIZE = 0.022f;

	/** How wide the ember column is, as a multiple of the body width. */
	private static final float SPREAD = 1.55f;

	/** How far in front of and behind the body the two layers sit, in body widths. */
	private static final float DEPTH = 0.50f;

	/** Below this alpha a mote is not worth a draw call. */
	private static final float MIN_ALPHA = 0.01f;

	/** Full brightness: these are embers, so they do not sit in shadow. */
	private static final int LIGHT = 0xF000F0;

	// render rather than renderLabelIfPresent: that one is only reached for an
	// entity that has a nameplate to draw, and the aura is not a nameplate.
	@Inject(method = "render", at = @At("HEAD"))
	private void spogtiers$submitAura(EntityRenderState state, MatrixStack matrices,
			OrderedRenderCommandQueue queue, CameraRenderState camera,
			CallbackInfo ci) {
		var config = SpogTiersClient.config();
		// The switch that already governs the door tag and the profile aura:
		// with our tierlist off, none of it shows anywhere.
		if (config == null || !config.enabled || !config.extraTierlists) {
			return;
		}
		PlayerGrade grade = AuraTarget.get(state);
		if (grade == null || !grade.isGraded()) {
			return;
		}

		// The player's own box, so the aura fits whoever it is around and
		// follows them down when they sneak.
		float height = state.height;
		float width = state.width;
		if (height <= 0.0f || width <= 0.0f) {
			return;
		}

		// Seconds, the same unit the profile aura counts in, so both read the
		// same moment of the same animation. Wrapped well short of float's
		// precision limit so the motion stays smooth on a long-lived world.
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null) {
			return;
		}
		float elapsed = (client.world.getTime() % 100000L
				+ client.getRenderTickCounter().getTickProgress(false)) / 20.0f;
		int rgb = grade.foreground() & 0xFFFFFF;

		for (int i = 0; i < WorldAura.MOTES; i++) {
			float alpha = AURA.alpha(i, elapsed);
			if (alpha <= MIN_ALPHA) {
				continue;
			}
			float across = AURA.across(i, elapsed);
			if (across < 0.0f) {
				continue;
			}

			// Across the body and up it, in the same normalised terms the
			// profile screen uses, then scaled into blocks.
			float x = (across - 0.5f) * SPREAD * width;
			float y = AURA.up(i, elapsed) * height;
			float z = (AURA.isFront(i) ? DEPTH : -DEPTH) * width;
			float half = (MIN_SIZE + AURA.size(i, elapsed) * (MAX_SIZE - MIN_SIZE)) * 0.5f;
			int colour = ((int) (alpha * 255.0f) << 24) | rgb;

			matrices.push();
			// Placed around the player in WORLD space first -- x across, y up,
			// z through -- and only then turned to face the camera. Rotating
			// before the offset would have applied x and z in camera space, so
			// the two layers would slide across the body as you walked round.
			matrices.translate(x, y, z);
			matrices.multiply(camera.orientation);

			float size = half;
			queue.getBatchingQueue(0).submitCustom(matrices, RenderLayers.textBackground(),
					(matrix, buffer) -> quad(matrix, buffer, colour, size));
			matrices.pop();
		}
	}

	/** One mote: a camera-facing square, wound the way vanilla winds its own. */
	private static void quad(MatrixStack.Entry matrix, VertexConsumer buffer, int colour, float half) {
		buffer.vertex(matrix, -half, -half, 0.0f).color(colour).light(LIGHT);
		buffer.vertex(matrix, -half, half, 0.0f).color(colour).light(LIGHT);
		buffer.vertex(matrix, half, half, 0.0f).color(colour).light(LIGHT);
		buffer.vertex(matrix, half, -half, 0.0f).color(colour).light(LIGHT);
	}
}
