package com.spog.tiers.util;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.network.chat.Component;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Remembers which name a render state was last tagged from.
 *
 * <p>{@code extractRenderState} is not guaranteed to run once per state per
 * frame: a subclass may call {@code super}, and another mod may re-extract to
 * pick up its own changes. Tagging reads the name, wraps it and writes it
 * back, so a second pass would wrap the already-wrapped name -- and since each
 * label draws its own translucent backdrop, every extra pass leaves another
 * quad stacked on the last, showing as a dark full-height bar wherever the
 * edges do not line up.
 *
 * <p>Storing the result rather than a flag also handles the name legitimately
 * changing: when it no longer matches what we produced, the state is tagged
 * afresh instead of being skipped forever.
 *
 * <p>Weakly keyed, so a render state that goes away takes its entry with it.
 */
public final class Tagged {
	private static final Map<EntityRenderState, Component> TAGGED = new WeakHashMap<>();

	private Tagged() {
	}

	/** True when {@code name} is one this class already produced for {@code state}. */
	public static boolean isTagged(EntityRenderState state, Component name) {
		Component previous = TAGGED.get(state);
		return previous != null && previous.equals(name);
	}

	public static void remember(EntityRenderState state, Component name) {
		TAGGED.put(state, name);
	}
}
