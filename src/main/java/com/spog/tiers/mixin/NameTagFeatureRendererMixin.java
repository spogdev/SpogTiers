package com.spog.tiers.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.spog.tiers.compat.NametagTweaks;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeStorage.NameTagSubmit;
import net.minecraft.client.renderer.feature.NameTagFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Takes over the nameplate's backdrop, and moves the plate as a whole.
 *
 * <p>Two jobs, both on the queue of plates just before it is drawn.
 *
 * <p><b>The backdrop.</b> Vanilla lets the font paint the box, as one more
 * thing emitted by the same draw call as the glyphs. That is fine until
 * something re-orders those draws: ImmediatelyFast's enhanced batching groups
 * geometry by render type and draws it a batch at a time, and the box is a
 * different render type from the glyphs, so it can land on top of them. A tier
 * icon comes from its own font, so it is a separate batch again -- and one that
 * an opaque box then hid completely, while the ordinary letters, drawn in a
 * batch of their own, came through. Drawing the box ourselves, before the text
 * and a hair behind it, settles it by depth rather than by order: at
 * {@link #BACKDROP_DEPTH} it fails the depth test wherever a glyph has already
 * written, whatever order the batches arrive in.
 *
 * <p><b>The height.</b> Nametag Tweaks raises a plate by subtracting its offset
 * from the {@code y} it hands the font, at the moment of drawing. Anything else
 * drawn from the same plate -- Essential's icon, and the two backdrop-coloured
 * strips it pads the plate with -- positions itself from the pose alone, so it
 * stayed at the original height while the text moved away, leaving two bars
 * beside the tag. Folding the offset into the pose and taking it back out of
 * the {@code y} lands the text exactly where it was and lifts the rest with it.
 */
@Mixin(NameTagFeatureRenderer.class)
public class NameTagFeatureRendererMixin {
	/**
	 * How far behind the glyphs the backdrop sits.
	 *
	 * <p>Vanilla's own {@code UNDER_EFFECT_DEPTH}, which is where it puts the
	 * box the font draws. Level with the text instead, the two would fight over
	 * the depth buffer and the text would flicker as the camera moved.
	 */
	private static final float BACKDROP_DEPTH = -0.01f;

	@Inject(method = "renderTranslucent", at = @At("HEAD"))
	private void spogtiers$ownBackdropAndHeight(SubmitNodeCollection collection,
			MultiBufferSource.BufferSource buffers, Font font, CallbackInfo ci) {
		NameTagStorageAccessor storage = (NameTagStorageAccessor) collection.getNameTagSubmits();
		List<NameTagSubmit> seeThrough = storage.spogtiers$seeThrough();
		List<NameTagSubmit> normal = storage.spogtiers$normal();

		float raise = NametagTweaks.plateOffset();
		if (raise != 0.0f) {
			lift(seeThrough, raise);
			lift(normal, raise);
		}

		// Drawn before the font is asked for anything, so the box is the first
		// of these render types to be used and cannot be batched after the
		// glyphs it belongs behind.
		//
		// Only the depth-tested layer. The see-through one has no depth test at
		// all, so it covers whatever was drawn before it rather than losing to
		// it -- taking that copy over buried the plate's own name.
		backdrop(normal, buffers, font, RenderTypes.textBackground());
		strip(normal);
	}

	/**
	 * Draws the box for every queued plate, in the colour that plate asked for.
	 *
	 * <p>The same rectangle vanilla's font would have drawn: a pixel of margin
	 * to the left and above, none to the right, nine rows of text below.
	 */
	private static void backdrop(List<NameTagSubmit> submits,
			MultiBufferSource.BufferSource buffers, Font font, RenderType layer) {
		for (NameTagSubmit submit : submits) {
			int colour = submit.backgroundColor();
			if ((colour & 0xFF000000) == 0) {
				continue;
			}
			float left = submit.x() - 1.0f;
			float top = submit.y() - 1.0f;
			float right = submit.x() + font.width(submit.text());
			float bottom = submit.y() + 9.0f;
			quad(buffers.getBuffer(layer), submit.pose(), colour, submit.lightCoords(),
					left, top, right, bottom);
		}
	}

	/** Clears each plate's backdrop, now that it is drawn by hand. */
	private static void strip(List<NameTagSubmit> submits) {
		submits.replaceAll(submit -> new NameTagSubmit(submit.pose(), submit.x(), submit.y(),
				submit.text(), submit.lightCoords(), submit.color(), 0,
				submit.distanceToCameraSq()));
	}

	/**
	 * Rebuilds each plate with the offset in its pose instead of its text.
	 *
	 * <p>The translate is in the pose's own space, where one unit is one font
	 * pixel and y runs downwards, so a negative y is upwards -- the same
	 * direction the mod's subtraction moves the text.
	 */
	private static void lift(List<NameTagSubmit> submits, float raise) {
		submits.replaceAll(submit -> new NameTagSubmit(
				new Matrix4f(submit.pose()).translate(0.0f, -raise, 0.0f),
				submit.x(), submit.y() + raise, submit.text(), submit.lightCoords(),
				submit.color(), submit.backgroundColor(), submit.distanceToCameraSq()));
	}

	/**
	 * One backdrop quad, wound the way vanilla winds its own.
	 *
	 * <p>Anticlockwise from the top left. The other winding is silently
	 * discarded: these layers cull back faces, with no error to say so.
	 */
	private static void quad(VertexConsumer buffer, Matrix4fc pose, int colour, int light,
			float left, float top, float right, float bottom) {
		buffer.addVertex(pose, left, top, BACKDROP_DEPTH).setColor(colour).setLight(light);
		buffer.addVertex(pose, left, bottom, BACKDROP_DEPTH).setColor(colour).setLight(light);
		buffer.addVertex(pose, right, bottom, BACKDROP_DEPTH).setColor(colour).setLight(light);
		buffer.addVertex(pose, right, top, BACKDROP_DEPTH).setColor(colour).setLight(light);
	}
}
