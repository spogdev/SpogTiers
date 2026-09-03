package com.spog.tiers.mixin;

import com.spog.tiers.util.AboveLabel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the above-name tag as its own label, stacked over the nameplate.
 *
 * <p>Submitted through the same {@code submitNameTag} vanilla uses for the
 * name, so the background, scale and fade are the game's own and wrap this
 * line's text rather than the wider of two lines.
 *
 * <p>The render state's {@code scoreText} would have been the obvious home,
 * but vanilla draws that <em>under</em> the name -- it is the scoreboard line.
 */
@Mixin(EntityRenderer.class)
public class LabelMixin {
	/**
	 * One line of vertical space, in the pixel units the offset argument takes.
	 *
	 * <p>Negative is up: the offset becomes the label's y coordinate directly,
	 * so this lifts our line clear of the name's.
	 *
	 * <p>Exactly nine, the line height vanilla uses between its own two lines.
	 * Ten left a pixel of this label's backdrop lying over the name's, and
	 * where two translucent backdrops overlap the alpha doubles and shows as a
	 * dark band.
	 */
	private static final int LINE_OFFSET = -9;

	@Inject(method = "submitNameDisplay(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;"
			+ "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;"
			+ "Lnet/minecraft/client/renderer/state/level/CameraRenderState;I)V",
			at = @At("TAIL"))
	private void spogtiers$submitAboveLabel(EntityRenderState state, PoseStack poseStack,
			SubmitNodeCollector collector, CameraRenderState camera, int offset,
			CallbackInfo ci) {
		Component above = AboveLabel.get(state);
		if (above == null || state.nameTagAttachment == null) {
			return;
		}
		// 26.2 dropped the camera-distance argument 26.1 took.
		collector.submitNameTag(poseStack, state.nameTagAttachment, offset + LINE_OFFSET,
				matchWidth(above, state.nameTag), !state.isDiscrete,
				state.lightCoords, camera);
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
	private static Component matchWidth(Component label, Component name) {
		if (name == null) {
			return label;
		}
		Font font = Minecraft.getInstance().font;
		int space = font.width(" ");
		if (space <= 0) {
			return label;
		}

		// The name's own asymmetry is unknown here, so cover the worst case: it
		// could be offset by a full space either way.
		int missing = font.width(name) - font.width(label) + space * 2;
		int each = Math.max(1, (missing + space * 2 - 1) / (space * 2));

		StringBuilder pad = new StringBuilder(each);
		for (int i = 0; i < each; i++) {
			pad.append(' ');
		}
		return Component.literal(pad.toString())
				.append(label)
				.append(Component.literal(pad.toString()));
	}
}
