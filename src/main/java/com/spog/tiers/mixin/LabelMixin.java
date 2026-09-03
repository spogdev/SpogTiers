package com.spog.tiers.mixin;

import com.spog.tiers.util.AboveLabel;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the above-name tag as its own label, stacked over the nameplate.
 *
 * <p>Submitted through the same {@code submitLabel} vanilla uses for the name,
 * so the background, scale and fade are the game's own and wrap this line's
 * text rather than the wider of two lines.
 */
@Mixin(EntityRenderer.class)
public class LabelMixin {
	/**
	 * One line of vertical space, matching what 26.x puts between its own two
	 * label lines: nine pixels at the label's scale.
	 */
	private static final float LINE_HEIGHT = 9.0f * 1.15f * 0.025f;

	@Inject(method = "renderLabelIfPresent", at = @At("TAIL"))
	private void spogtiers$submitAboveLabel(EntityRenderState state, MatrixStack matrices,
			OrderedRenderCommandQueue queue, CameraRenderState camera, CallbackInfo ci) {
		Text above = AboveLabel.get(state);
		if (above == null || state.nameLabelPos == null) {
			return;
		}

		matrices.push();
		// Negative is up. Vanilla draws its own upper line at 0 and then
		// translates by +LINE_HEIGHT to put the name underneath, so positive Y
		// here is downward -- translating the other way put this tag below the
		// name instead of above it.
		matrices.translate(0.0f, -LINE_HEIGHT, 0.0f);
		queue.submitLabel(matrices, state.nameLabelPos, 0,
				matchWidth(above, state.displayName), !state.sneaking,
				state.light, state.squaredDistanceToCamera, camera);
		matrices.pop();
	}

	/**
	 * Pads {@code label} with spaces until it is at least as wide as the name.
	 *
	 * <p>Each label carries its own backdrop, sized to its own text and centred
	 * on it, so two lines of different widths draw two concentric rectangles.
	 * The narrower one's left and right edges then fall *inside* the wider one,
	 * and each edge shows as a full-height bar of doubled alpha -- the grey
	 * lines, which no vertical offset can remove because the mismatch is
	 * horizontal.
	 *
	 * <p>Padding both sides keeps the text centred while making the two
	 * backdrops the same width, so their edges coincide instead of stacking.
	 * Space is the only lever available here: the backdrop is drawn inside the
	 * text renderer from the text alone, with no width to pass.
	 */
	private static Text matchWidth(Text label, Text name) {
		if (name == null) {
			return label;
		}
		TextRenderer font = MinecraftClient.getInstance().textRenderer;
		int target = font.getWidth(name);
		int space = font.getWidth(" ");
		if (space <= 0) {
			return label;
		}

		// Round outwards: a backdrop a shade wider than the name hides its edge
		// behind the name's own, while a shade narrower would leave the bar.
		int missing = target - font.getWidth(label);
		if (missing <= 0) {
			return label;
		}
		int each = (missing + space * 2 - 1) / (space * 2);

		StringBuilder pad = new StringBuilder(each);
		for (int i = 0; i < each; i++) {
			pad.append(' ');
		}
		return Text.literal(pad.toString())
				.append(label)
				.append(Text.literal(pad.toString()));
	}
}
