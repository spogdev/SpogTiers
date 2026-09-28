package dev.spog.tiers.mixin;

import dev.spog.tiers.client.QuickTiers;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;

/**
 * Registers our key binding with vanilla.
 *
 * <p>Fabric's key-binding module is compiled against intermediary and cannot be
 * loaded on 26.x (see PORTING.md), so the binding is appended to
 * {@code Options.keyMappings} directly. Being in that array is what makes the
 * game draw it in the Controls screen and write the chosen key to options.txt.
 *
 * <p>The injection has to land <em>before</em> the constructor's trailing
 * {@code load()} call, not at RETURN. {@code load()} applies options.txt by
 * walking {@code keyMappings} and matching each entry's {@code key_<name>} line;
 * a binding missing from the array at that moment has its saved line read and
 * discarded. Injecting at RETURN meant the key was saved correctly but dropped
 * on every load, so the binding silently reverted to its default each restart.
 */
@Mixin(Options.class)
public abstract class OptionsMixin {
	@Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;load()V"))
	private void spogtiers$addKeyMappings(CallbackInfo info) {
		Options options = (Options) (Object) this;
		KeyMapping binding = QuickTiers.binding();

		// The field is final, but only the reference is: a longer array has to
		// be written back through the accessor below.
		for (KeyMapping existing : options.keyMappings) {
			if (existing == binding) {
				return;
			}
		}

		KeyMapping[] grown = Arrays.copyOf(options.keyMappings, options.keyMappings.length + 1);
		grown[grown.length - 1] = binding;
		((OptionsAccessor) options).spogtiers$setKeyMappings(grown);
	}
}
