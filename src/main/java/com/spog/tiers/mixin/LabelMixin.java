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
	 * Pads {@code label} with spaces until its backdrop covers the name's.
	 *
	 * <p>Each label carries its own backdrop, sized to its own text and centred
	 * on it, so two lines of different widths draw two concentric rectangles.
	 * The narrower one's left and right edges then fall *inside* the wider one,
	 * and each edge shows as a full-height bar of doubled alpha -- the grey
	 * lines, which no vertical offset can remove because the mismatch is
	 * horizontal.
	 *
	 * <p>Padding to strictly wider than the name, by a whole space on each
	 * side, is what actually hides them. Matching the width exactly is not
	 * enough: the name is asymmetric -- {@code TagRenderer} appends a leading
	 * space only when something sits left of the name and a trailing one only
	 * when something sits right -- so two equally wide backdrops still centre
	 * differently, and whichever edge falls short leaves its bar behind. Going
	 * over on both sides puts our edges outside the name's entirely, where they
	 * sit against the sky and cannot double.
	 */
	private static Text matchWidth(Text label, Text name) {
		if (name == null) {
			return label;
		}
		TextRenderer font = MinecraftClient.getInstance().textRenderer;
		int space = font.getWidth(" ");
		if (space <= 0) {
			return label;
		}

		// The name's own asymmetry is unknown here, so cover the worst case: it
		// could be offset by a full space either way.
		int missing = font.getWidth(name) - font.getWidth(label) + space * 2;
		int each = Math.max(1, (missing + space * 2 - 1) / (space * 2));

		StringBuilder pad = new StringBuilder(each);
		for (int i = 0; i < each; i++) {
			pad.append(' ');
		}
		return Text.literal(pad.toString())
				.append(label)
				.append(Text.literal(pad.toString()));
	}
}
