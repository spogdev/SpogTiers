package dev.spog.tiers.client.gui;

import dev.spog.spore.ui.theme.SporeDraw;
import dev.spog.spore.ui.theme.SporeTheme;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.PressableWidget;
import net.minecraft.client.input.AbstractInput;
import net.minecraft.text.Text;

import java.util.function.Consumer;

/**
 * A flat button drawn in the profile panel's own style, so it sits with the
 * cards instead of looking like a stock Minecraft widget.
 */
public class PanelButton extends PressableWidget {
	private static final int FILL = 0x60161B22;
	private static final int FILL_HOVERED = 0x8022303F;
	private static final int BORDER = 0x70323B47;
	private static final int BORDER_HOVERED = 0xA05B6B7D;
	private static final int TEXT = 0xFFE4EAF2;

	private final Consumer<PanelButton> onPress;

	public PanelButton(int x, int y, int width, int height, Text message,
			Consumer<PanelButton> onPress) {
		super(x, y, width, height, message);
		this.onPress = onPress;
	}

	@Override
	public void onPress(AbstractInput input) {
		onPress.accept(this);
	}

	/**
	 * PressableWidget paints the vanilla button texture in a final
	 * renderWidget, so the panel styling goes here instead -- this is the hook
	 * it leaves open for subclasses.
	 */
	@Override
	protected void drawIcon(DrawContext context, int mouseX, int mouseY, float delta) {
		int left = getX();
		int top = getY();
		int right = left + width;
		int bottom = top + height;

		boolean hovered = isHovered();
		int fill = hovered ? FILL_HOVERED : FILL;
		int border = hovered ? BORDER_HOVERED : BORDER;

		// The library's control corner, so this button matches the ones
		// SporeUI draws on the settings screen beside it.
		SporeDraw.roundedPanel(context, left, top, right, bottom,
				SporeTheme.CONTROL_RADIUS, fill, border);

		TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;
		context.drawTextWithShadow(textRenderer, getMessage(),
				left + (width - textRenderer.getWidth(getMessage())) / 2,
				top + (height - textRenderer.fontHeight) / 2,
				TEXT);
	}

	@Override
	protected void appendClickableNarrations(NarrationMessageBuilder builder) {
		appendDefaultNarrations(builder);
	}
}
