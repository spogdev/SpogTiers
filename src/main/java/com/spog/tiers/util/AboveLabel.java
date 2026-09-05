package com.spog.tiers.util;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.network.chat.Component;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Carries the extra tag rows from where they are resolved to where they are
 * drawn.
 *
 * <p>This version's {@code EntityRenderState} has no field for a second label
 * line -- 26.x added one -- so the value is parked here between the two
 * mixins rather than folded into the name, where it would share the name's
 * single background.
 *
 * <p>Weakly keyed, so a render state that goes away takes its entry with it.
 * Render states are pooled and reused, so entries are overwritten rather than
 * accumulating.
 */
public final class AboveLabel {
	private static final Map<EntityRenderState, Component> ABOVE = new WeakHashMap<>();
	private static final Map<EntityRenderState, Component> BELOW = new WeakHashMap<>();

	private AboveLabel() {
	}

	public static void set(EntityRenderState state, Component label) {
		put(ABOVE, state, label);
	}

	public static Component get(EntityRenderState state) {
		return ABOVE.get(state);
	}

	/** The row drawn under the name, which the tag editor can also fill. */
	public static void setBelow(EntityRenderState state, Component label) {
		put(BELOW, state, label);
	}

	public static Component getBelow(EntityRenderState state) {
		return BELOW.get(state);
	}

	private static void put(Map<EntityRenderState, Component> into,
			EntityRenderState state, Component label) {
		if (label == null) {
			into.remove(state);
		} else {
			into.put(state, label);
		}
	}
}
