package com.spog.tiers.mixin;

import com.spog.tiers.compat.NametagTweaks;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Moves a whole nameplate, not just its text, when Nametag Tweaks raises it.
 *
 * <p>That mod raises a plate by subtracting its offset from the {@code y} it
 * hands the font, at the moment of drawing. Anything else drawn from the
 * same nameplate -- Essential's icon, and the two backdrop-coloured strips it
 * pads the plate with -- positions itself from the plate's anchor alone, so it
 * stays at the original height while the text and its box move away. With a
 * translucent black backdrop that left two faint bars beside the plate; with
 * a solid coloured one it left two solid bars.
 *
 * <p>Fixed here by raising the anchor the plate is built around, before any of
 * that runs, and letting the mod's own subtraction pull the text back down to
 * where it already was. Everything positioned from the anchor rises with it.
 *
 * <p>On 26.1.2 the same fix rewrites the queued submits, because the plate is
 * assembled before anything can be intercepted. Here the anchor arrives as an
 * argument, so it can simply be moved on the way in.
 */
@Mixin(SubmitNodeCollection.class)
public class NameTagFeatureRendererMixin {
	/**
	 * Vanilla's nameplate scale: one font pixel is this many blocks.
	 *
	 * <p>The offset is counted in font pixels, and the anchor is in world
	 * units, so the two need converting between. Upwards is positive here --
	 * the {@code -y} flip that makes the mod's subtraction a rise happens
	 * inside the plate's own frame, not out here.
	 */
	private static final double SCALE = 0.025;

	@ModifyVariable(method = "submitNameTag", at = @At("HEAD"), argsOnly = true, index = 2)
	private Vec3 spogtiers$raiseWholePlate(Vec3 attachment) {
		float raise = NametagTweaks.plateOffset();
		if (raise == 0.0f || attachment == null) {
			return attachment;
		}
		return attachment.add(0.0, raise * SCALE, 0.0);
	}
}
