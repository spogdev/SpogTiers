package com.spog.tiers.util;

import com.spog.tiers.data.PlayerGrade;
import net.minecraft.client.render.entity.state.EntityRenderState;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Carries a player's tier from where it is looked up to where the aura is
 * drawn.
 *
 * <p>The render state holds no reference back to its entity, so the tier has
 * to be parked here between extraction and submission, the same way the
 * above-name tag is.
 *
 * <p>Weakly keyed, so a render state that goes away takes its entry with it.
 * Render states are pooled and reused, so entries are overwritten rather than
 *
 * <p>Deliberately outside {@code com.spog.tiers.mixin}: everything in that
 * package is claimed by the mixin config, and a plain class there cannot be
 * referenced from normal code -- loading it throws at render time.
 * accumulating.
 */
public final class AuraTarget {
	private static final Map<EntityRenderState, PlayerGrade> GRADES = new WeakHashMap<>();

	private AuraTarget() {
	}

	public static void set(EntityRenderState state, PlayerGrade grade) {
		if (grade == null) {
			GRADES.remove(state);
		} else {
			GRADES.put(state, grade);
		}
	}

	public static PlayerGrade get(EntityRenderState state) {
		return GRADES.get(state);
	}
}
