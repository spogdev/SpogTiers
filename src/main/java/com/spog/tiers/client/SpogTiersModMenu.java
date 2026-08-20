package com.spog.tiers.client;

import com.spog.tiers.client.gui.ConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Exposes the settings screen through ModMenu.
 *
 * <p>ModMenu is a compile-only dependency: this entrypoint is simply never
 * loaded when it is absent, so the mod runs fine without it.
 */
public class SpogTiersModMenu implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return ConfigScreen::new;
	}
}
