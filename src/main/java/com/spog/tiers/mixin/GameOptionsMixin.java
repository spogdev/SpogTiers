package com.spog.tiers.mixin;

import com.spog.tiers.client.QuickTiers;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;

/**
 * Registers our key binding with vanilla.
 *
 * <p>Being in {@code GameOptions.allKeys} is what makes the game draw the
 * binding in the Controls screen and persist the chosen key to options.txt,
 * so appending to that array is all that is needed.
 */
@Mixin(GameOptions.class)
public abstract class GameOptionsMixin {
	@Inject(method = "<init>", at = @At("RETURN"))
	private void spogtiers$addKeyBindings(CallbackInfo info) {
		GameOptions options = (GameOptions) (Object) this;
		KeyBinding binding = QuickTiers.binding();

		for (KeyBinding existing : options.allKeys) {
			if (existing == binding) {
				return;
			}
		}

		KeyBinding[] grown = Arrays.copyOf(options.allKeys, options.allKeys.length + 1);
		grown[grown.length - 1] = binding;
		((GameOptionsAccessor) options).spogtiers$setAllKeys(grown);
	}
}
