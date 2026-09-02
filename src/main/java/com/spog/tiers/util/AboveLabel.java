package com.spog.tiers.util;

import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.text.Text;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Carries the above-name tag from where it is resolved to where it is drawn.
 *
 * <p>This version's {@code EntityRenderState} has no field for a second label
 * line, so the value is parked here between the two mixins rather than folded
 * into the name, where it would share the name's single background.
 *
 * <p>Deliberately outside {@code com.spog.tiers.mixin}: everything in that
 * package is claimed by the mixin config, and a plain class there cannot be
 * referenced from normal code -- loading it throws at render time.
 *
 * <p>Weakly keyed, so a render state that goes away takes its entry with it.
 * Render states are pooled and reused, so entries are overwritten rather than
 * accumulating.
 */
public final class AboveLabel {
	private static final Map<EntityRenderState, Text> LABELS = new WeakHashMap<>();

	private AboveLabel() {
	}

	public static void set(EntityRenderState state, Text label) {
		if (label == null) {
			LABELS.remove(state);
		} else {
			LABELS.put(state, label);
		}
	}

	public static Text get(EntityRenderState state) {
		return LABELS.get(state);
	}
}
