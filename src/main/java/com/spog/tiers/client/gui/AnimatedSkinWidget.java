package com.spog.tiers.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerSkinWidget;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.PlayerSkin;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * A {@link PlayerSkinWidget} that idles rather than standing perfectly still:
 * the body rises and falls a little and the arms drift in and out, the way the
 * skin preview in the Modrinth app does.
 *
 * <p>Vanilla keeps its two {@code PlayerModel} instances private and rebuilds
 * the pose from the render state each frame, so the pose is applied here just
 * before the widget extracts its own render state.
 */
public class AnimatedSkinWidget extends PlayerSkinWidget {
	/** Seconds for one full breath in and out. */
	private static final float BREATH_PERIOD = 4.0f;
	/** Seconds for one full arm sway, deliberately offset from the breath. */
	private static final float SWAY_PERIOD = 5.5f;

	private static final float BODY_LIFT = 0.22f;
	private static final float ARM_SWING = 0.022f;
	private static final float ARM_PITCH = 0.018f;

	private final List<PlayerModel> models = new ArrayList<>();
	private float elapsed;

	public AnimatedSkinWidget(int width, int height, EntityModelSet models, Supplier<PlayerSkin> skin) {
		super(width, height, models, skin);
		collectModels();
	}

	/**
	 * Grabs the widget's own wide and slim models. They are private with no
	 * accessor, and mixing in to vanilla for a cosmetic idle is not worth the
	 * porting cost, so this reads them reflectively and simply does nothing if
	 * the fields ever move.
	 */
	private void collectModels() {
		for (Field field : PlayerSkinWidget.class.getDeclaredFields()) {
			if (!PlayerModel.class.isAssignableFrom(field.getType())) {
				continue;
			}
			try {
				field.setAccessible(true);
				Object value = field.get(this);
				if (value instanceof PlayerModel model) {
					models.add(model);
				}
			} catch (ReflectiveOperationException | RuntimeException ignored) {
				// Leave the widget static rather than failing to draw at all.
			}
		}
	}

	@Override
	public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		elapsed += partialTick / 20.0f;
		animate();
		super.extractWidgetRenderState(graphics, mouseX, mouseY, partialTick);
	}

	private void animate() {
		if (models.isEmpty()) {
			return;
		}

		float breath = Mth.sin(elapsed / BREATH_PERIOD * Mth.TWO_PI);
		float sway = Mth.sin(elapsed / SWAY_PERIOD * Mth.TWO_PI);

		for (PlayerModel model : models) {
			// Chest rises, and the head follows a beat later so it does not
			// look bolted to the body.
			model.body.y = 0.0f + breath * BODY_LIFT;
			model.head.y = breath * BODY_LIFT;
			model.hat.y = model.head.y;

			// Arms drift away from the torso and back, mirrored left to right.
			float swing = ARM_SWING + sway * ARM_SWING * 0.6f;
			float pitch = sway * ARM_PITCH;

			pose(model.rightArm, pitch, swing);
			pose(model.leftArm, pitch, -swing);
			pose(model.rightSleeve, pitch, swing);
			pose(model.leftSleeve, pitch, -swing);

			model.rightArm.y = breath * BODY_LIFT;
			model.leftArm.y = model.rightArm.y;
			model.rightSleeve.y = model.rightArm.y;
			model.leftSleeve.y = model.rightArm.y;
		}
	}

	private static void pose(ModelPart part, float xRot, float zRot) {
		part.xRot = xRot;
		part.zRot = zRot;
	}
}
