package com.spog.tiers.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.spog.tiers.SpogTiersClient;
import com.spog.tiers.util.DisplayAbove;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.minecraft.client.renderer.entity.state.TextDisplayEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the above-name tag over a text-display nametag, as its own strip.
 *
 * <p>The display's own text is left untouched. Vanilla sizes one background
 * quad to the widest line, so folding the tag in as a second line left empty
 * bands of colour beside the shorter of the two. This draws a separate quad
 * sized to the tag alone, sitting directly above, and copies the display's
 * background colour, opacity, alignment and see-through flag so it reads as
 * part of the same label rather than something stuck on top.
 */
@Mixin(DisplayRenderer.TextDisplayRenderer.class)
public abstract class TextDisplayAboveMixin {
	/** Vanilla's line height: nine pixels plus a pixel of leading. */
	private static final int LINE_HEIGHT = 10;

	/** The display's own scale, so our strip sits in the same units. */
	private static final float SCALE = -0.025f;

	@Shadow
	private Display.TextDisplay.CachedInfo splitLines(Component text, int lineWidth) {
		throw new AssertionError("shadow");
	}

	@Inject(method = "submitInner(Lnet/minecraft/client/renderer/entity/state/TextDisplayEntityRenderState;"
			+ "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;IF)V",
			at = @At("TAIL"))
	private void spogtiers$submitAbove(TextDisplayEntityRenderState state, PoseStack poseStack,
			SubmitNodeCollector collector, int light, float partialTick, CallbackInfo ci) {
		if (SpogTiersClient.config() == null || !SpogTiersClient.config().tagDisplays) {
			return;
		}
		Component above = DisplayAbove.get(state);
		Display.TextDisplay.TextRenderState text = state.textRenderState;
		if (above == null || text == null || state.cachedInfo == null) {
			return;
		}

		// Laid out with the display's own splitter, so the tag wraps and
		// measures exactly as its text would.
		Display.TextDisplay.CachedInfo info = splitLines(above, text.lineWidth());
		if (info.lines().isEmpty()) {
			return;
		}

		byte flags = text.flags();
		boolean seeThrough = (flags & Display.TextDisplay.FLAG_SEE_THROUGH) != 0;
		boolean defaultBackground = (flags & Display.TextDisplay.FLAG_USE_DEFAULT_BACKGROUND) != 0;
		Display.TextDisplay.Align align = Display.TextDisplay.getAlign(flags);

		int opacity = text.textOpacity().get(partialTick);
		int background = defaultBackground
				? (int) (Minecraft.getInstance().gameRenderer.gameRenderState()
						.optionsRenderState.getBackgroundOpacity(0.25f) * 255.0f) << 24
				: text.backgroundColor().get(partialTick);

		int ourHeight = info.lines().size() * LINE_HEIGHT - 1;
		int theirHeight = state.cachedInfo.lines().size() * LINE_HEIGHT - 1;

		poseStack.pushPose();
		Matrix4f pose = poseStack.last().pose();
		pose.rotate((float) Math.PI, 0.0f, 1.0f, 0.0f);
		pose.scale(SCALE, SCALE, SCALE);
		// Up by the display's own height, so the strip sits directly on top of
		// it with no gap and no overlap.
		pose.translate(1.0f - info.width() / 2.0f, -(ourHeight + theirHeight), 0.0f);

		if ((background & 0xFC000000) != 0) {
			int width = info.width();
			int height = ourHeight;
			collector.submitCustomGeometry(poseStack,
					seeThrough ? RenderTypes.textBackgroundSeeThrough() : RenderTypes.textBackground(),
					(matrix, buffer) -> background(matrix, buffer, background, light, width, height));
		}

		var ordered = collector.order(background != 0 ? 1 : 0);
		int line = 0;
		for (Display.TextDisplay.CachedLine cached : info.lines()) {
			float x = switch (align) {
				case LEFT -> 0.0f;
				case RIGHT -> info.width() - cached.width();
				default -> (info.width() - cached.width()) / 2.0f;
			};
			ordered.submitText(poseStack, x, line * LINE_HEIGHT, cached.contents(),
					(flags & Display.TextDisplay.FLAG_SHADOW) != 0,
					seeThrough ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL,
					0, opacity << 24 | 0xFFFFFF, light, 0);
			line++;
		}
		poseStack.popPose();
	}

	/** The background quad, in the same shape vanilla builds for its own. */
	private static void background(PoseStack.Pose matrix, VertexConsumer consumer,
			int colour, int light, int width, int height) {
		consumer.addVertex(matrix, -1.0f, -1.0f, 0.0f).setColor(colour).setLight(light);
		consumer.addVertex(matrix, -1.0f, height, 0.0f).setColor(colour).setLight(light);
		consumer.addVertex(matrix, width, height, 0.0f).setColor(colour).setLight(light);
		consumer.addVertex(matrix, width, -1.0f, 0.0f).setColor(colour).setLight(light);
	}
}
