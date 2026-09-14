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
 * game draw it in the Controls screen and persist the chosen key to
 * options.txt, so nothing else is needed.
 */
@Mixin(Options.class)
public abstract class OptionsMixin {
	@Inject(method = "<init>", at = @At("RETURN"))
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
