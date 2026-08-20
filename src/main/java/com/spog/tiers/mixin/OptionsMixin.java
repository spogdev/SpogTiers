package com.spog.tiers.mixin;

import com.spog.tiers.SpogTiersClient;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;

/**
 * Registers our keybind by appending it to vanilla's {@code keyMappings} array,
 * so it shows up in Controls like any other binding.
 *
 * <p>Fabric API's key-binding module would normally do this, but on 26.x it is
 * compiled against intermediary names that our identity mappings cannot resolve
 * (see PORTING.md), so we register directly instead. The field is final in
 * vanilla, hence {@link Mutable}.
 */
@Mixin(Options.class)
public class OptionsMixin {
	@Mutable
	@Final
	@Shadow
	public KeyMapping[] keyMappings;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void spogtiers$registerKeybinds(CallbackInfo ci) {
		KeyMapping binding = SpogTiersClient.openProfileKey();
		if (binding == null || Arrays.asList(keyMappings).contains(binding)) {
			return;
		}

		KeyMapping[] extended = Arrays.copyOf(keyMappings, keyMappings.length + 1);
		extended[extended.length - 1] = binding;
		keyMappings = extended;
	}
}
