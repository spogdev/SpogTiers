package com.spog.tiers.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.spog.tiers.compat.NametagTweaks;
import com.spog.tiers.util.AboveLabel;
import com.spog.tiers.util.NameShift;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the above-name tag as its own line, stacked over the nameplate.
 *
 * <p>Drawn by hand rather than through {@code submitNameTag}. A vanilla label
 * always paints its backdrop across the full width of its text, so the only
 * way to control that box was to pad the text -- and padding it out to the
 * name's width, which stopped the two lines' backdrops fighting, left a box
 * far wider than the tag inside it. Drawing our own quad sizes the box to the
 * icon and text exactly, and keeps it clear of the name's box by construction:
 * its bottom edge is the name's top edge, sharing pixels with nothing.
 *
 * <p>Everything else copies vanilla's nameplate so the line looks like part
 * of it: the same anchor, camera billboarding and scale, the same backdrop
 * opacity option, and the same two text passes -- faint through walls, solid
 * when in view -- with the same colours and emissive light.
 *
 * <p>The render state's {@code scoreText} would have been the obvious home,
 * but vanilla draws that <em>under</em> the name -- it is the scoreboard line.
 */
@Mixin(EntityRenderer.class)
public class LabelMixin {
	/**
	 * One line up, in font pixels: nine of text plus the backdrop's pixel of
	 * margin, so this line's box ends exactly where the name's begins.
	 */
	private static final int LINE_OFFSET = -10;

	/** Vanilla's nameplate scale: one font pixel is this many blocks. */
	private static final float SCALE = 0.025f;

	/** Vanilla's text colour for the pass that shows through walls. */
	private static final int FAINT = 0x80FFFFFF;

	/** Vanilla's text colour for the pass that shows when in view. */
	private static final int SOLID = 0xFFFFFFFF;

	/** Vanilla's emission for the in-view pass, so text never sits in shadow. */
	private static final int EMISSION = 2;

	@Inject(method = "submitNameDisplay(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;"
			+ "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;"
			+ "Lnet/minecraft/client/renderer/state/level/CameraRenderState;I)V",
			at = @At("TAIL"))
	private void spogtiers$submitAboveLabel(EntityRenderState state, PoseStack poseStack,
			SubmitNodeCollector collector, CameraRenderState camera, int offset,
			CallbackInfo ci) {
		Component above = AboveLabel.get(state);
		Component below = AboveLabel.getBelow(state);
		Vec3 attachment = state.nameTagAttachment;
		if ((above == null && below == null) || attachment == null) {
			return;
		}
		// Nothing without a name to hang off: when that mod is hiding the
		// plate, rows floating on their own read as a bug.
		if (NametagTweaks.hidden()) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		Font font = minecraft.font;
		boolean seeThrough = !state.isDiscrete;
		int light = state.lightCoords;
		// The plate's own colour when that mod is setting one, so a row does
		// not sit on vanilla's translucent black under a recoloured name.
		int background = NametagTweaks.background(
				(int) (minecraft.gameRenderer.gameRenderState()
						.optionsRenderState.getBackgroundOpacity(0.25f) * 255.0f) << 24);

		// The same frame vanilla builds for the name: anchored at the label
		// point, turned to face the camera, and scaled so that one unit is one
		// font pixel with y running downwards.
		// Nametag Tweaks moves and resizes the plate itself by wrapping
		// vanilla's own drawing, which our rows never go through. Reading the
		// same numbers keeps the three lines together instead of leaving two
		// of them at the old height and size.
		float tweakScale = NametagTweaks.scale();
		poseStack.pushPose();
		poseStack.translate(attachment.x, attachment.y + 0.5, attachment.z);
		poseStack.mulPose(camera.orientation);
		poseStack.scale(SCALE * tweakScale, -SCALE * tweakScale, SCALE * tweakScale);

		// Centred over the name rather than over the whole plate, when asked:
		// the middle row's own width includes its tiers, so a long tier on one
		// side would otherwise push the other rows off to the side of the name.
		float shift = nameShift(state, font);
		// Subtracted the way the mod subtracts it from the plate's own
		// height, and inside the scaled frame, so a raised plate takes its
		// rows with it by the same amount rather than a different one.
		float raise = NametagTweaks.offset();
		if (above != null) {
			line(collector, poseStack, font, above, offset + LINE_OFFSET - raise, shift,
					seeThrough, light, background);
		}
		// One line below the name rather than above it, by the same pitch, so
		// the three rows are evenly spaced whichever of them are filled.
		if (below != null) {
			line(collector, poseStack, font, below, offset - LINE_OFFSET - raise, shift,
					seeThrough, light, background);
		}
		poseStack.popPose();
	}

	/**
	 * How far the extra rows move to sit over the name, in font pixels.
	 *
	 * <p>Zero unless the layout asks for it. The name plate is centred on its
	 * whole text, so the name's own middle is offset from that by half of
	 * whatever sits either side of it; moving the other rows by the same
	 * amount lines all three up on the name.
	 */
	private static float nameShift(EntityRenderState state, Font font) {
		var config = com.spog.tiers.SpogTiersClient.config();
		if (config == null || !config.tagLayout.centerOnName) {
			return 0.0f;
		}
		Component before = NameShift.before(state);
		Component after = NameShift.after(state);
		int left = before == null ? 0 : font.width(before);
		int right = after == null ? 0 : font.width(after);
		return (left - right) / 2.0f;
	}

	/** One extra row, backdrop and both text passes, at {@code y} font pixels. */
	private static void line(SubmitNodeCollector collector, PoseStack poseStack, Font font,
			Component text, float y, float shift, boolean seeThrough, int light,
			int background) {
		int width = font.width(text);
		float x = -width / 2.0f + shift;

		if ((background & 0xFF000000) != 0) {
			// The box vanilla would draw for this text: a pixel of margin on
			// the left and above, none on the right, nine rows of text below.
			float left = x - 1.0f;
			float top = y - 1.0f;
			float right = x + width;
			float bottom = y + 9.0f;
			collector.submitCustomGeometry(poseStack,
					seeThrough ? RenderTypes.textBackgroundSeeThrough() : RenderTypes.textBackground(),
					(pose, buffer) -> quad(pose, buffer, background, light, left, top, right, bottom));
		}

		FormattedCharSequence ordered = text.getVisualOrderText();
		// A shadow when the plate has one, so the rows are not the only text
		// on the tag without it.
		boolean shadow = NametagTweaks.textShadow();
		var queue = collector.order(1);
		if (seeThrough) {
			queue.submitText(poseStack, x, y, ordered, shadow, Font.DisplayMode.SEE_THROUGH,
					light, FAINT, 0, 0);
			queue.submitText(poseStack, x, y, ordered, shadow, Font.DisplayMode.NORMAL,
					LightCoordsUtil.lightCoordsWithEmission(light, EMISSION), SOLID, 0, 0);
		} else {
			queue.submitText(poseStack, x, y, ordered, shadow, Font.DisplayMode.NORMAL,
					light, FAINT, 0, 0);
		}
	}

	/** One backdrop quad, wound the way vanilla winds its own. */
	private static void quad(PoseStack.Pose pose, VertexConsumer buffer, int colour, int light,
			float left, float top, float right, float bottom) {
		buffer.addVertex(pose, left, top, 0.0f).setColor(colour).setLight(light);
		buffer.addVertex(pose, left, bottom, 0.0f).setColor(colour).setLight(light);
		buffer.addVertex(pose, right, bottom, 0.0f).setColor(colour).setLight(light);
		buffer.addVertex(pose, right, top, 0.0f).setColor(colour).setLight(light);
	}
}
