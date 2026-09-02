package com.spog.tiers.mixin;

import com.spog.tiers.SpogTiersClient;
import com.spog.tiers.util.DisplayAbove;
import com.spog.tiers.util.TagRenderer;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.minecraft.client.renderer.entity.state.TextDisplayEntityRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tags nametags that a server draws with a text display rather than the
 * player's own nameplate.
 *
 * <p>Servers do this to colour or style a name, which vanilla does not allow
 * on a real nameplate. The display is a separate entity riding the player, so
 * {@link EntityRendererMixin} never sees it and the tag would silently go
 * missing on exactly the servers that care most about how names look.
 *
 * <p>Matching is by the name in the text, since a display carries no link back
 * to the player it labels. See {@link TagRenderer#taggedDisplay}.
 */
@Mixin(DisplayRenderer.TextDisplayRenderer.class)
public abstract class TextDisplayMixin {
	/**
	 * The renderer's own line splitter.
	 *
	 * <p>Needed because the cached layout describes the old text: the renderer
	 * dereferences it without a null check, so it has to be replaced rather
	 * than cleared.
	 */
	@Shadow
	private Display.TextDisplay.CachedInfo splitLines(Component text, int lineWidth) {
		throw new AssertionError("shadow");
	}

	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void spogtiers$tagTextDisplay(Display.TextDisplay display,
			TextDisplayEntityRenderState state, float partialTick, CallbackInfo ci) {
		if (SpogTiersClient.config() == null
				|| !SpogTiersClient.config().showNametags
				|| !SpogTiersClient.config().tagDisplays) {
			return;
		}
		Display.TextDisplay.TextRenderState text = state.textRenderState;
		if (text == null || text.text() == null) {
			return;
		}

		// The above tag is stashed for TextDisplayAboveMixin rather than folded
		// into the text: vanilla sizes one background to the widest line, so a
		// second line here leaves empty colour beside the shorter one.
		DisplayAbove.set(state, TagRenderer.displayAboveTag(text.text()));

		Component tagged = TagRenderer.taggedDisplay(text.text());
		if (tagged == null) {
			return;
		}

		// The record is immutable, so the whole state is rebuilt with the new
		// text and every other field carried across untouched.
		state.textRenderState = new Display.TextDisplay.TextRenderState(
				tagged, text.lineWidth(), text.textOpacity(),
				text.backgroundColor(), text.flags());
		// Re-laid out rather than cleared. submitInner reads width() straight
		// off this, so a null here crashes the render thread on the next frame.
		state.cachedInfo = splitLines(tagged, text.lineWidth());
	}
}
