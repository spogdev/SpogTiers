package dev.spog.tiers.util;

import net.minecraft.client.renderer.entity.state.TextDisplayEntityRenderState;
import net.minecraft.network.chat.Component;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Carries the above-name tag for a text-display nametag from where it is
 * resolved to where it is drawn.
 *
 * <p>The display's render state has nowhere to put it, and it deliberately
 * does not go into the display's own text: vanilla sizes one background quad
 * to the widest line, so a second line there leaves empty colour beside the
 * shorter one. It is drawn as its own strip instead.
 *
 * <p>Outside {@code dev.spog.tiers.mixin} because everything in that package
 * is claimed by the mixin config and cannot be referenced from normal code.
 *
 * <p>Weakly keyed, so a render state that goes away takes its entry with it.
 */
public final class DisplayAbove {
	private static final Map<TextDisplayEntityRenderState, Component> LABELS = new WeakHashMap<>();

	private DisplayAbove() {
	}

	public static void set(TextDisplayEntityRenderState state, Component label) {
		if (label == null) {
			LABELS.remove(state);
		} else {
			LABELS.put(state, label);
		}
	}

	public static Component get(TextDisplayEntityRenderState state) {
		return LABELS.get(state);
	}
}
