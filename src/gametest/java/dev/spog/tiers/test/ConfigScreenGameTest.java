package dev.spog.tiers.test;

import dev.spog.tiers.client.gui.ConfigScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screen.TitleScreen;

import java.util.Locale;

/**
 * Opens the settings screen and visits every tab in a real client.
 *
 * <p>The screen moved onto SporeUI, which means the four settings tabs are now
 * drawn by the library rather than by this mod, while the Tier Tagger tab is
 * still drawn by its own editor through the library's bare-tab hooks.
 * Compiling proves the call sites match; it proves nothing about whether the
 * panel still paints, because all of that happens inside
 * {@code render}, which the compiler checks and never runs.
 *
 * <p>It asserts nothing about the pixels. A screenshot comparison would need
 * regenerating on every deliberate change, and what is worth catching here is
 * a screen that throws or lays out wrongly -- which is what looking at the
 * screenshots catches.
 */
public class ConfigScreenGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		// Built on the client thread: the constructor reaches
		// Minecraft.getInstance() for the font the nametag editor measures
		// with, which throws off it.
		ConfigScreen screen = context.computeOnClient(client -> new ConfigScreen(null));
		context.setScreen(() -> screen);
		context.waitForScreen(ConfigScreen.class);

		// GUI scale 1, so the panel is measured at the size a player on a
		// normal window sees. The harness runs an 854x480 window, which at the
		// default scale of 2 is only 427x240 logical -- short enough that
		// every tab overflows and the test would be screenshotting a scrollbar
		// rather than the layout.
		context.runOnClient(client -> {
			client.options.getGuiScale().setValue(1);
			client.onResolutionChanged();
		});
		context.waitTicks(5);

		for (ConfigScreen.Tab tab : ConfigScreen.Tab.values()) {
			context.runOnClient(client -> screen.openTabForTest(tab));
			context.waitTicks(5);
			context.takeScreenshot("spogtiers-" + tab.name().toLowerCase(Locale.ROOT));
		}

		// The harness requires the client to end on the title screen, so one
		// test cannot leave a screen open for the next to trip over.
		context.setScreen(TitleScreen::new);
		context.waitForScreen(TitleScreen.class);
	}
}
