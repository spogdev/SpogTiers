package dev.spog.tiers.mixin;

import dev.spog.tiers.compat.NametagTweaks;
import dev.spog.tiers.util.AboveLabel;
import dev.spog.tiers.util.NameShift;
import dev.spog.tiers.util.RowPadding;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
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
 * <p>The backdrop is the font's own, carried by the first text pass only. A
 * box on the second pass would paint over what the first drew, icons included.
 *
 * <p>Everything else copies vanilla's nameplate so the line looks like part
 * of it: the same anchor, camera billboarding and scale, the same backdrop
 * opacity option, and the same two text passes -- faint through walls, solid
 * when in view -- with the same colours and emissive light.
 */
// PlayerEntityRenderer overrides renderLabelIfPresent, and players are the
// only thing we tag -- so a mixin on EntityRenderer alone never fires for
// them and the extra rows simply never drew. Both are targeted, and the
// guard below means the base class contributes nothing for a player.
@Mixin({EntityRenderer.class, PlayerEntityRenderer.class})
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
		// Every row out to the width of the widest, when asked. The backdrop
		// is the font's and is only as wide as the text it is given, so three
		// rows of different lengths stack up as a ragged set of boxes. The
		// plate itself is padded where it is set, on the render state.
		int widest = RowPadding.widest(state.displayName, above, below);
		if (above != null) {
			line(queue, matrices, font, RowPadding.row(above, widest), LINE_OFFSET - raise, shift,
					seeThrough, light, background);
		}
		// One line below the name rather than above it, by the same pitch, so
		// the three rows are evenly spaced whichever of them are filled.
		if (below != null) {
			line(queue, matrices, font, RowPadding.row(below, widest), -LINE_OFFSET - raise, shift,
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
		var config = dev.spog.tiers.SpogTiersClient.config();
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
	 * <p>The backdrop is handed to the font rather than drawn as our own
	 * geometry. Both were tried, and the difference is which phase draws
	 * them: the label commands are drawn before the text, and custom geometry
	 * last of all. A depth-writing quad in that last phase went down after
	 * every phase before it, so water behind a row was rejected against depth
	 * the box had already written -- while the plate beside it, drawn on the
	 * label phase and depth-sorted with the rest, composited over the water
	 * properly.
	 *
	 * <p>Only the first pass carries it. The box spans the whole line, so a
	 * second one paints over what the first pass drew; and every text render
	 * layer shares a buffer flushed on each layer change, which makes the
	 * tier icons -- from their own texture -- a separate flush for a later box
	 * to land on. That is what buried the icons when both passes carried one.
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
		var batch = queue.getBatchingQueue(0);
		// The font's own backdrop, on the first pass only -- see above for
		// why it is not our own geometry, and why only one pass carries it.
		if (seeThrough) {
			batch.submitText(matrices, x, y, ordered, shadow, TextRenderer.TextLayerType.SEE_THROUGH,
					light, FAINT, background, 0);
			batch.submitText(matrices, x, y, ordered, shadow, TextRenderer.TextLayerType.NORMAL,
					LightmapTextureManager.applyEmission(light, EMISSION), SOLID, 0, 0);
		} else {
			batch.submitText(matrices, x, y, ordered, shadow, TextRenderer.TextLayerType.NORMAL,
					light, FAINT, background, 0);
		}
	}

}
