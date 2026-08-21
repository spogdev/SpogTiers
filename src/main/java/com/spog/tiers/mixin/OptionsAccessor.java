package com.spog.tiers.mixin;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Writes back {@code Options.keyMappings}, which is final. */
@Mixin(Options.class)
public interface OptionsAccessor {
	@Accessor("keyMappings")
	@Mutable
	void spogtiers$setKeyMappings(KeyMapping[] value);
}
