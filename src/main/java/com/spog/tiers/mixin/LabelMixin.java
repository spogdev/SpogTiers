package com.spog.tiers.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.spog.tiers.compat.NametagTweaks;
import com.spog.tiers.util.AboveLabel;
import com.spog.tiers.util.NameShift;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
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
 * <p>Drawn through {@code submitText} rather than {@code submitNameTag}. The
 * two look the same -- the same font, backdrop and pair of passes -- but a
 * name-tag submit is what other mods hook to decorate a nameplate, and each
 * of them would decorate every row: Essential paints its icon and padding on
 * every name-tag submit it sees. A text submit is nobody's nameplate.
 *
 * <p>The backdrop is the font's own, handed to it as the background colour
 * rather than drawn as a separate quad. It has to be: text submits are drawn
 * before custom geometry, so a quad of our own landed <em>over</em> the row.
 * At vanilla's quarter-opaque black that only dimmed the text slightly; at
 * the solid colours Nametag Tweaks allows it buried the row entirely, icon
 * and all, and the row's colour read as the wrong shade through it.
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
		// Which of its two branches applies depends on the pass, so the
		// choice is made per row rather than once here.
		int vanillaBackground = (int) (minecraft.gameRenderer.gameRenderState()
				.optionsRenderState.getBackgroundOpacity(0.25f) * 255.0f) << 24;
		int background = NametagTweaks.background(vanillaBackground, seeThrough);

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
		// The mod moves the plate by changing the y it hands the font, inside
		// the nameplate's own renderer. Our rows go through a different path,
		// so the same subtraction is applied here or they stay put while the
		// plate rises.
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

	/**
	 * One extra row, drawn the way vanilla draws a nameplate.
	 *
	 * <p>Only the first pass carries the backdrop, though vanilla hands one to
	 * both of its own. Ours cannot: vanilla's two passes are separate submits
	 * flushed in two batches, so its second box lands under its second copy of
	 * the text, while both of ours go through one queue -- the second box is
	 * drawn after the first pass's glyphs and covers them. At a translucent
	 * colour that only dimmed them; at an opaque one it erased the tier icons
	 * outright, since they are drawn once and not repainted by the second
	 * pass the way the text is.
	 *
	 * <p>The see-through pass goes first, carrying the box and the faint text,
	 * and the in-view pass second with the solid emissive text over it. That
	 * is the order {@code renderTranslucent} walks its two lists in.
	 */
	private static void line(SubmitNodeCollector collector, PoseStack poseStack, Font font,
			Component text, float y, float shift, boolean seeThrough, int light,
			int background) {
		int width = font.width(text);
		float x = -width / 2.0f + shift;
		FormattedCharSequence ordered = text.getVisualOrderText();
		// A shadow when the plate has one, so the rows are not the only text
		// on the tag without it.
		boolean shadow = NametagTweaks.textShadow();
		var queue = collector.order(1);
		if (seeThrough) {
			queue.submitText(poseStack, x, y, ordered, shadow, Font.DisplayMode.SEE_THROUGH,
					light, FAINT, background, 0);
			queue.submitText(poseStack, x, y, ordered, shadow, Font.DisplayMode.NORMAL,
					LightCoordsUtil.lightCoordsWithEmission(light, EMISSION), SOLID, 0, 0);
		} else {
			queue.submitText(poseStack, x, y, ordered, shadow, Font.DisplayMode.NORMAL,
					light, FAINT, background, 0);
		}
	}
}
