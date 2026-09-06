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
import net.minecraft.client.renderer.rendertype.RenderType;
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
 * <p>Drawn through {@code submitText} rather than {@code submitNameTag}. The
 * two look the same -- the same font, backdrop and pair of passes -- but a
 * name-tag submit is what other mods hook to decorate a nameplate, and each
 * of them would decorate every row: Essential paints its icon and padding on
 * every name-tag submit it sees. A text submit is nobody's nameplate.
 *
 * <p>The backdrop is drawn as our own geometry rather than handed to the font
 * as a background colour, and submitted at a lower order than the glyphs so it
 * lands behind them. The font's own box cannot be used here: it is emitted
 * once per text pass, and a second pass's box paints over icons the first pass
 * already drew.
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

	/**
	 * How far behind the glyphs the backdrop sits, as vanilla puts its own.
	 *
	 * <p>Vanilla's {@code UNDER_EFFECT_DEPTH}, copied rather than chosen. A box
	 * level with the text is coplanar with it, and two coplanar surfaces fight
	 * over the depth buffer: the text flickered as the camera moved. Sitting
	 * behind also settles which one wins without relying on draw order, so an
	 * icon cannot be painted over by a box that happens to be batched later.
	 */
	private static final float BACKDROP_DEPTH = -0.01f;

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
		int vanillaBackground = (int) (minecraft.gameRenderer.getGameRenderState()
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
	 * One extra row: its backdrop as our own geometry, then both text passes.
	 *
	 * <p>The backdrop cannot be the font's own. Every text render type shares
	 * one buffer that is flushed whenever the type changes, so a batch is drawn
	 * in submission order -- and the tier icons come from their own texture,
	 * which makes them a separate flush from the letters beside them. A second
	 * pass carrying a box therefore paints over icons the first pass had
	 * already drawn, while the letters, redrawn by that same pass, survive.
	 * That is why an opaque colour erased the icons and left the text.
	 *
	 * <p>Drawn instead as two quads submitted before the glyphs, at a lower
	 * order: {@code renderTranslucentFeatures} walks the orders in turn, and
	 * custom geometry in one order runs before text in the next, so the box is
	 * always behind what sits on it.
	 *
	 * <p>Two quads, not one, because vanilla queues an opaque-backdrop plate on
	 * both of its lists. The see-through copy has no depth test and shows
	 * through walls; the in-view copy is depth tested, and is what stops water
	 * and hitbox lines drawn later from crossing the box. A row with only the
	 * first had them cutting straight through it.
	 */
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
			// Seen-through first and in-view second, the order the nameplate's
			// own two lists are walked in.
			if (seeThrough) {
				backdrop(collector, poseStack, RenderTypes.textBackgroundSeeThrough(),
						background, light, left, top, right, bottom);
			}
			backdrop(collector, poseStack, RenderTypes.textBackground(),
					background, light, left, top, right, bottom);
		}

		FormattedCharSequence ordered = text.getVisualOrderText();
		// A shadow when the plate has one, so the rows are not the only text
		// on the tag without it.
		boolean shadow = NametagTweaks.textShadow();
		// A later order than the backdrop, so the glyphs land on top of it.
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

	/** One backdrop quad on one layer, at the default order. */
	private static void backdrop(SubmitNodeCollector collector, PoseStack poseStack,
			RenderType layer, int colour, int light,
			float left, float top, float right, float bottom) {
		collector.submitCustomGeometry(poseStack, layer,
				(pose, buffer) -> quad(pose, buffer, colour, light, left, top, right, bottom));
	}

	/**
	 * One backdrop quad, wound the way vanilla winds its own.
	 *
	 * <p>Anticlockwise from the top left. The other winding is silently
	 * discarded: these layers cull back faces, with no error to say so.
	 */
	private static void quad(PoseStack.Pose pose, VertexConsumer buffer, int colour, int light,
			float left, float top, float right, float bottom) {
		buffer.addVertex(pose, left, top, BACKDROP_DEPTH).setColor(colour).setLight(light);
		buffer.addVertex(pose, left, bottom, BACKDROP_DEPTH).setColor(colour).setLight(light);
		buffer.addVertex(pose, right, bottom, BACKDROP_DEPTH).setColor(colour).setLight(light);
		buffer.addVertex(pose, right, top, BACKDROP_DEPTH).setColor(colour).setLight(light);
	}
}
