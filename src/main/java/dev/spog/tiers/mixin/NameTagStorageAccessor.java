package dev.spog.tiers.mixin;

import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.NameTagFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** Reads the two lists of queued nameplates, which are private. */
@Mixin(NameTagFeatureRenderer.Storage.class)
public interface NameTagStorageAccessor {
	@Accessor("nameTagSubmitsSeethrough")
	List<SubmitNodeStorage.NameTagSubmit> spogtiers$seeThrough();

	@Accessor("nameTagSubmitsNormal")
	List<SubmitNodeStorage.NameTagSubmit> spogtiers$normal();
}
