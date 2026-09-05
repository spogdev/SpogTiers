package com.spog.tiers.mixin;

import com.spog.tiers.compat.NametagTweaks;
import com.spog.tiers.util.AboveLabel;
import com.spog.tiers.util.NameShift;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the above-name tag as its own line, stacked over the nameplate.
 *
 * <p>Drawn through {@code submitText} rather than {@code submitLabel}. The
 * two look the same -- the same font, backdrop and pair of passes -- but a
 * label command is what other mods hook to decorate a nameplate, and each
 * of them would decorate every row: Essential paints its icon and padding on
 * every label command it sees. A text command is nobody's nameplate.
 *
 * <p>The backdrop is the font's own, handed to it as the background colour
 * rather than drawn as a separate quad. It has to be: text commands are drawn
 * before custom geometry, so a quad of our own landed <em>over</em> the row.
 * At vanilla's quarter-opaque black that only dimmed the text slightly; at
 * the solid colours Nametag Tweaks allows it buried the row entirely, icon
 * and all, and the row's colour read as the wrong shade through it.
 *
 * <p>Everything else copies vanilla's nameplate so the line looks like part
 * of it: the same anchor, camera billboarding and scale, the same backdrop
 * opacity option, and the same two text passes -- faint through walls, solid
 * when in view -- with the same colours and emissive light.
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

	@Inject(method = "renderLabelIfPresent", at = @At("TAIL"))
	private void spogtiers$submitAboveLabel(EntityRenderState state, MatrixStack matrices,
			OrderedRenderCommandQueue queue, CameraRenderState camera, CallbackInfo ci) {
		Text above = AboveLabel.get(state);
		Text below = AboveLabel.getBelow(state);
		Vec3d attachment = state.nameLabelPos;
		if ((above == null && below == null) || attachment == null) {
			return;
		}
		// Nothing without a name to hang off: when that mod is hiding the
		// plate, rows floating on their own read as a bug.
		if (NametagTweaks.hidden()) {
			return;
		}

		MinecraftClient client = MinecraftClient.getInstance();
		TextRenderer font = client.textRenderer;
		boolean seeThrough = !state.sneaking;
		int light = state.light;
		// The plate's own colour when that mod is setting one, so a row does
		// not sit on vanilla's translucent black under a recoloured name.
		// Which of its two branches applies depends on the pass, so the
		// choice is made per row rather than once here.
		int vanillaBackground =
				(int) (client.options.getTextBackgroundOpacity(0.25f) * 255.0f) << 24;
		int background = NametagTweaks.background(vanillaBackground, seeThrough);

		// The same frame vanilla builds for the name: anchored at the label
		// point, turned to face the camera, and scaled so that one unit is one
		// font pixel with y running downwards.
		// Nametag Tweaks resizes the plate by wrapping vanilla's own drawing,
		// which our rows never go through: without this they stay at the old
		// size while the middle line grows.
		float tweakScale = NametagTweaks.scale();
		matrices.push();
		matrices.translate(attachment.x, attachment.y + 0.5, attachment.z);
		matrices.multiply(camera.orientation);
		matrices.scale(SCALE * tweakScale, -SCALE * tweakScale, SCALE * tweakScale);

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
			line(queue, matrices, font, above, LINE_OFFSET - raise, shift,
					seeThrough, light, background);
		}
		// One line below the name rather than above it, by the same pitch, so
		// the three rows are evenly spaced whichever of them are filled.
		if (below != null) {
			line(queue, matrices, font, below, -LINE_OFFSET - raise, shift,
					seeThrough, light, background);
		}
		matrices.pop();
	}

	/**
	 * How far the extra rows move to sit over the name, in font pixels.
	 *
	 * <p>Zero unless the layout asks for it. The name plate is centred on its
	 * whole text, so the name's own middle is offset from that by half of
	 * whatever sits either side of it; moving the other rows by the same
	 * amount lines all three up on the name.
	 */
	private static float nameShift(EntityRenderState state, TextRenderer font) {
		var config = com.spog.tiers.SpogTiersClient.config();
		if (config == null || !config.tagLayout.centerOnName) {
			return 0.0f;
		}
		Text before = NameShift.before(state);
		Text after = NameShift.after(state);
		int left = before == null ? 0 : font.getWidth(before);
		int right = after == null ? 0 : font.getWidth(after);
		return (left - right) / 2.0f;
	}

	/**
	 * One extra row, drawn the way vanilla draws a nameplate.
	 *
	 * <p>Both passes carry the backdrop, not just one. That looks like a bug
	 * and is not: vanilla queues an opaque-backdrop label on <em>both</em> its
	 * lists, each with the same background colour, so the box is composited
	 * twice and the plate is darker than one pass of it would be. A row that
	 * painted its box once came out visibly lighter than the name beside it --
	 * measurably so: with the plate red at half alpha, the plate reads
	 * {@code E02B40} and a single-pass row {@code C1567F}.
	 *
	 * <p>Order matters as much as count. The see-through pass goes first,
	 * carrying the faint text, and the in-view pass second with the solid
	 * emissive text; that is the order {@code LabelCommandRenderer.render}
	 * walks its two lists in. Reversing them paints the second backdrop over
	 * the first pass's glyphs, which is what buried the tier icons.
	 */
	private static void line(OrderedRenderCommandQueue queue, MatrixStack matrices,
			TextRenderer font, Text text, float y, float shift, boolean seeThrough,
			int light, int background) {
		int width = font.getWidth(text);
		float x = -width / 2.0f + shift;
		OrderedText ordered = text.asOrderedText();
		// A shadow when the plate has one, so the rows are not the only text
		// on the tag without it.
		boolean shadow = NametagTweaks.textShadow();
		var batch = queue.getBatchingQueue(1);
		if (seeThrough) {
			batch.submitText(matrices, x, y, ordered, shadow, TextRenderer.TextLayerType.SEE_THROUGH,
					light, FAINT, background, 0);
			batch.submitText(matrices, x, y, ordered, shadow, TextRenderer.TextLayerType.NORMAL,
					LightmapTextureManager.applyEmission(light, EMISSION), SOLID, background, 0);
		} else {
			batch.submitText(matrices, x, y, ordered, shadow, TextRenderer.TextLayerType.NORMAL,
					light, FAINT, background, 0);
		}
	}
}
