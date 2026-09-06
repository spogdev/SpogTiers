package com.spog.tiers.mixin;

import com.spog.tiers.compat.NametagTweaks;
import com.spog.tiers.util.AboveLabel;
import com.spog.tiers.util.NameShift;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
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
 * <p>The backdrop is drawn as our own geometry rather than handed to the font
 * as a background colour, and submitted on an earlier batching queue than the
 * glyphs so it lands behind them. The font's own box cannot be used here: it
 * is emitted once per text pass, and a second pass's box paints over icons the
 * first pass already drew.
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

	/**
	 * How far behind the glyphs the backdrop sits, as vanilla puts its own.
	 *
	 * <p>Vanilla.s {@code UNDER_EFFECT_DEPTH}, copied rather than chosen. A box
	 * level with the text is coplanar with it, and two coplanar surfaces fight
	 * over the depth buffer: the text flickered as the camera moved. Sitting
	 * behind also settles which one wins without relying on draw order, so an
	 * icon cannot be painted over by a box that happens to be batched later.
	 */
	private static final float BACKDROP_DEPTH = -0.01f;

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
	 * <p>The backdrop cannot be the font's own. Every text render layer shares
	 * one buffer that is flushed whenever the layer changes, so a batch is
	 * drawn in submission order -- and the tier icons come from their own
	 * texture, which makes them a separate flush from the letters beside them.
	 * A second pass carrying a box therefore paints over icons the first pass
	 * had already drawn, while the letters, redrawn by that same pass, survive.
	 * That is why an opaque colour erased the icons and left the text.
	 *
	 * <p>Drawn instead as two quads submitted before the glyphs, on an earlier
	 * batching queue: the queues are drawn in turn, so custom geometry on one
	 * runs before text on the next and the box stays behind what sits on it.
	 *
	 * <p>Two quads, not one, because vanilla queues an opaque-backdrop label on
	 * both of its lists. The see-through copy has no depth test and shows
	 * through walls; the in-view copy is depth tested, and is what stops water
	 * and hitbox lines drawn later from crossing the box. A row with only the
	 * first had them cutting straight through it.
	 */
	private static void line(OrderedRenderCommandQueue queue, MatrixStack matrices,
			TextRenderer font, Text text, float y, float shift, boolean seeThrough,
			int light, int background) {
		int width = font.getWidth(text);
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
				backdrop(queue, matrices, RenderLayers.textBackgroundSeeThrough(),
						background, light, left, top, right, bottom);
			}
			backdrop(queue, matrices, RenderLayers.textBackground(),
					background, light, left, top, right, bottom);
		}

		OrderedText ordered = text.asOrderedText();
		// A shadow when the plate has one, so the rows are not the only text
		// on the tag without it.
		boolean shadow = NametagTweaks.textShadow();
		// A later queue than the backdrop, so the glyphs land on top of it.
		var batch = queue.getBatchingQueue(1);
		if (seeThrough) {
			batch.submitText(matrices, x, y, ordered, shadow, TextRenderer.TextLayerType.SEE_THROUGH,
					light, FAINT, 0, 0);
			batch.submitText(matrices, x, y, ordered, shadow, TextRenderer.TextLayerType.NORMAL,
					LightmapTextureManager.applyEmission(light, EMISSION), SOLID, 0, 0);
		} else {
			batch.submitText(matrices, x, y, ordered, shadow, TextRenderer.TextLayerType.NORMAL,
					light, FAINT, 0, 0);
		}
	}

	/** One backdrop quad on one layer, on the earlier batching queue. */
	private static void backdrop(OrderedRenderCommandQueue queue, MatrixStack matrices,
			RenderLayer layer, int colour, int light,
			float left, float top, float right, float bottom) {
		queue.getBatchingQueue(0).submitCustom(matrices, layer,
				(matrix, buffer) -> quad(matrix, buffer, colour, light, left, top, right, bottom));
	}

	/**
	 * One backdrop quad, wound the way vanilla winds its own.
	 *
	 * <p>Anticlockwise from the top left. The other winding is silently
	 * discarded: these layers cull back faces, with no error to say so.
	 */
	private static void quad(MatrixStack.Entry matrix, VertexConsumer buffer, int colour, int light,
			float left, float top, float right, float bottom) {
		buffer.vertex(matrix, left, top, BACKDROP_DEPTH).color(colour).light(light);
		buffer.vertex(matrix, left, bottom, BACKDROP_DEPTH).color(colour).light(light);
		buffer.vertex(matrix, right, bottom, BACKDROP_DEPTH).color(colour).light(light);
		buffer.vertex(matrix, right, top, BACKDROP_DEPTH).color(colour).light(light);
	}
}
