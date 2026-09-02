package com.spog.tiers.mixin;

import com.spog.tiers.SpogTiersClient;
import com.spog.tiers.util.TagRenderer;
import net.minecraft.client.render.entity.DisplayEntityRenderer;
import net.minecraft.client.render.entity.state.TextDisplayEntityRenderState;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.text.Text;
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
@Mixin(DisplayEntityRenderer.TextDisplayEntityRenderer.class)
public abstract class TextDisplayMixin {
	/**
	 * The renderer's own line splitter.
	 *
	 * <p>Needed because the cached lines describe the old text: the renderer
	 * dereferences them without a null check, so they have to be replaced
	 * rather than cleared.
	 */
	@Shadow
	private DisplayEntity.TextDisplayEntity.TextLines getLines(Text text, int lineWidth) {
		throw new AssertionError("shadow");
	}

	@Inject(method = "updateRenderState", at = @At("TAIL"))
	private void spogtiers$tagTextDisplay(DisplayEntity.TextDisplayEntity display,
			TextDisplayEntityRenderState state, float tickDelta, CallbackInfo ci) {
		if (SpogTiersClient.config() == null
				|| !SpogTiersClient.config().showNametags
				|| !SpogTiersClient.config().tagDisplays) {
			return;
		}
		DisplayEntity.TextDisplayEntity.Data data = state.data;
		if (data == null || data.text() == null) {
			return;
		}

		Text tagged = TagRenderer.taggedDisplay(data.text());
		if (tagged == null) {
			return;
		}

		// The record is immutable, so the whole thing is rebuilt with the new
		// text and every other field carried across untouched.
		state.data = new DisplayEntity.TextDisplayEntity.Data(
				tagged, data.lineWidth(), data.textOpacity(),
				data.backgroundColor(), data.flags());
		// Re-split rather than cleared. The renderer reads the width straight
		// off these, so a null here crashes the render thread on the next frame.
		state.textLines = getLines(tagged, data.lineWidth());
	}
}
