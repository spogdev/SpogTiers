package dev.spog.tiers.mixin;

import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Writes back {@code GameOptions.allKeys}, which is final. */
@Mixin(GameOptions.class)
public interface GameOptionsAccessor {
	@Accessor("allKeys")
	@Mutable
	void spogtiers$setAllKeys(KeyBinding[] value);
}
