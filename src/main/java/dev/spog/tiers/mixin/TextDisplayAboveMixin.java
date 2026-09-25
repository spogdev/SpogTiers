package dev.spog.tiers.mixin;

import dev.spog.tiers.SpogTiersClient;
import dev.spog.tiers.compat.NametagTweaks;
import dev.spog.tiers.util.DisplayAbove;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.DisplayEntityRenderer;
import net.minecraft.client.render.entity.state.TextDisplayEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.text.Text;
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
@Mixin(DisplayEntityRenderer.TextDisplayEntityRenderer.class)
public abstract class TextDisplayAboveMixin {
	/** Vanilla's line height: nine pixels plus a pixel of leading. */
	private static final int LINE_HEIGHT = 10;

	/** The display's own scale, so our strip sits in the same units. */
	private static final float SCALE = -0.025f;

	@Shadow
	private DisplayEntity.TextDisplayEntity.TextLines getLines(Text text, int lineWidth) {
		throw new AssertionError("shadow");
	}

	@Inject(method = "render", at = @At("TAIL"))
	private void spogtiers$submitAbove(TextDisplayEntityRenderState state, MatrixStack matrices,
			OrderedRenderCommandQueue queue, int light, float tickDelta, CallbackInfo ci) {
		if (SpogTiersClient.config() == null || !SpogTiersClient.config().tagDisplays) {
			return;
		}
		Text above = DisplayAbove.get(state);
		DisplayEntity.TextDisplayEntity.Data data = state.data;
		if (above == null || data == null || state.textLines == null) {
			return;
		}

		// Laid out with the display's own splitter, so the tag wraps and
		// measures exactly as its text would.
		DisplayEntity.TextDisplayEntity.TextLines lines = getLines(above, data.lineWidth());
		if (lines.lines().isEmpty()) {
			return;
		}

		byte flags = data.flags();
		boolean seeThrough = (flags & DisplayEntity.TextDisplayEntity.SEE_THROUGH_FLAG) != 0;
		boolean defaultBackground =
				(flags & DisplayEntity.TextDisplayEntity.DEFAULT_BACKGROUND_FLAG) != 0;
		DisplayEntity.TextDisplayEntity.TextAlignment align =
				DisplayEntity.TextDisplayEntity.getAlignment(flags);

		int opacity = data.textOpacity().lerp(tickDelta);
		int background = defaultBackground
				? (int) (MinecraftClient.getInstance().options.getTextBackgroundOpacity(0.25f)
						* 255.0f) << 24
				: data.backgroundColor().lerp(tickDelta);

		int ourHeight = lines.lines().size() * LINE_HEIGHT - 1;

		// Auto Expand: the strip is normally only as wide as its own text, so
		// a short tag over a long display leaves two boxes of different widths
		// stacked on each other. Widened to the display's own text instead --
		// set directly rather than padded with spaces, because unlike the
		// nameplate this background is a quad we size ourselves.
		int stripWidth = lines.width();
		if (SpogTiersClient.config().tagLayout.autoExpand) {
			stripWidth = Math.max(stripWidth, state.textLines.width());
		}

		// Vanilla never pushes here, so at TAIL the matrix is already rotated,
		// scaled and translated into the display's text space -- rotating and
		// scaling again would put this strip somewhere else entirely. It only
		// needs moving up, in the units that space already uses.
		matrices.push();
		matrices.peek().getPositionMatrix().translate(
				(state.textLines.width() - stripWidth) / 2.0f,
				-(ourHeight + 1),
				0.0f);

		if ((background & 0xFC000000) != 0) {
			int width = stripWidth;
			int height = ourHeight;
			queue.getBatchingQueue(0).submitCustom(matrices,
					seeThrough ? RenderLayers.textBackgroundSeeThrough()
							: RenderLayers.textBackground(),
					(matrix, buffer) -> background(matrix, buffer, background, light, width, height));
		}

		var ordered = queue.getBatchingQueue(background != 0 ? 1 : 0);
		// The display's own flag, but still subject to the Text Shadow setting:
		// matching the server's tag is the default, and turning shadows off
		// should turn them off everywhere this mod draws text.
		boolean shadow = (flags & DisplayEntity.TextDisplayEntity.SHADOW_FLAG) != 0
				&& NametagTweaks.textShadow();
		int line = 0;
		for (DisplayEntity.TextDisplayEntity.TextLine cached : lines.lines()) {
			float x = switch (align) {
				case LEFT -> 0.0f;
				case RIGHT -> stripWidth - cached.width();
				default -> (stripWidth - cached.width()) / 2.0f;
			};
			ordered.submitText(matrices, x, line * LINE_HEIGHT, cached.contents(),
					shadow,
					seeThrough ? TextRenderer.TextLayerType.SEE_THROUGH
							: TextRenderer.TextLayerType.NORMAL,
					0, opacity << 24 | 0xFFFFFF, light, 0);
			line++;
		}
		matrices.pop();
	}

	/** The background quad, in the same shape vanilla builds for its own. */
	private static void background(MatrixStack.Entry matrix, VertexConsumer consumer,
			int colour, int light, int width, int height) {
		consumer.vertex(matrix, -1.0f, -1.0f, 0.0f).color(colour).light(light);
		consumer.vertex(matrix, -1.0f, height, 0.0f).color(colour).light(light);
		consumer.vertex(matrix, width, height, 0.0f).color(colour).light(light);
		consumer.vertex(matrix, width, -1.0f, 0.0f).color(colour).light(light);
	}
}
