package dev.spog.tiers.mixin;

import net.minecraft.client.render.command.LabelCommandRenderer;
import net.minecraft.client.render.command.OrderedRenderCommandQueueImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** Reads the two lists of queued nameplates, which are not public. */
@Mixin(LabelCommandRenderer.Commands.class)
public interface NameTagStorageAccessor {
	@Accessor("seethroughLabels")
	List<OrderedRenderCommandQueueImpl.LabelCommand> spogtiers$seeThrough();

	@Accessor("normalLabels")
	List<OrderedRenderCommandQueueImpl.LabelCommand> spogtiers$normal();
}
