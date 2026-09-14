package dev.spog.tiers.util;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.network.chat.Component;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * What sits either side of the name on the middle row.
 *
 * <p>Carried from where the row is built to where the other rows are drawn,
 * so those can be lined up over the name rather than over the whole plate.
 * The two are measured by the renderer rather than here, because only it has
 * the font.
 *
 * <p>Weakly keyed, like {@link AboveLabel}: render states are pooled and
 * reused, so entries are overwritten rather than accumulating, and one that
 * goes away takes its entry with it.
 */
public final class NameShift {
	private static final Map<EntityRenderState, Component> BEFORE = new WeakHashMap<>();
	private static final Map<EntityRenderState, Component> AFTER = new WeakHashMap<>();

	private NameShift() {
	}

	public static void set(EntityRenderState state, Component before, Component after) {
		put(BEFORE, state, before);
		put(AFTER, state, after);
	}

	public static Component before(EntityRenderState state) {
		return BEFORE.get(state);
	}

	public static Component after(EntityRenderState state) {
		return AFTER.get(state);
	}

	private static void put(Map<EntityRenderState, Component> into,
			EntityRenderState state, Component value) {
		if (value == null) {
			into.remove(state);
		} else {
			into.put(state, value);
		}
	}
}
