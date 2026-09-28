package dev.spog.tiers.mixin;

import dev.spog.tiers.client.QuickTiers;
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
 * binding in the Controls screen and write the chosen key to options.txt.
 *
 * <p>The injection has to land <em>before</em> the constructor's trailing
 * {@code load()} call, not at RETURN. {@code load()} applies options.txt by
 * walking {@code allKeys} and matching each entry's {@code key_<name>} line;
 * a binding missing from the array at that moment has its saved line read and
 * discarded. Injecting at RETURN meant the key was saved correctly but dropped
 * on every load, so the binding silently reverted to its default each restart.
 */
@Mixin(GameOptions.class)
public abstract class GameOptionsMixin {
	@Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/option/GameOptions;load()V"))
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
