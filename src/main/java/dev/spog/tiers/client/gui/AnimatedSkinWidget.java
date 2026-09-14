package dev.spog.tiers.client.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.PlayerSkinWidget;
import net.minecraft.client.render.entity.model.LoadedEntityModels;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.util.math.MathHelper;
import net.minecraft.entity.player.SkinTextures;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A {@link PlayerSkinWidget} that idles rather than standing perfectly still:
 * the whole body rises and falls a little and the arms drift in and out, the
 * way the skin preview in the Modrinth app does.
 *
 * <p>Vanilla keeps its two {@code PlayerEntityModel} instances private and rebuilds
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

	private final List<PlayerEntityModel> models = new ArrayList<>();
	/** Rest y of every animated part, so offsets never accumulate. */
	private final Map<ModelPart, Float> restY = new HashMap<>();

	private float elapsed;

	public AnimatedSkinWidget(int width, int height, LoadedEntityModels models, Supplier<SkinTextures> skin) {
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
			if (!PlayerEntityModel.class.isAssignableFrom(field.getType())) {
				continue;
			}
			try {
				field.setAccessible(true);
				if (field.get(this) instanceof PlayerEntityModel model) {
					models.add(model);
					rememberRest(model);
				}
			} catch (ReflectiveOperationException | RuntimeException ignored) {
				// Leave the widget static rather than failing to draw at all.
			}
		}
	}

	/**
	 * Parts do not all rest at y=0 -- arms and legs sit lower down the rig --
	 * so their rest positions are captured once and everything is animated as
	 * an offset from those.
	 */
	private void rememberRest(PlayerEntityModel model) {
		for (ModelPart part : parts(model)) {
			restY.putIfAbsent(part, part.originY);
		}
	}

	private static ModelPart[] parts(PlayerEntityModel model) {
		return new ModelPart[] {
			model.head, model.hat, model.body, model.jacket,
			model.rightArm, model.leftArm, model.rightSleeve, model.leftSleeve,
			model.rightLeg, model.leftLeg, model.rightPants, model.leftPants,
		};
	}

	@Override
	protected void renderWidget(DrawContext graphics, int mouseX, int mouseY, float partialTick) {
		elapsed += partialTick / 20.0f;
		animate();
		super.renderWidget(graphics, mouseX, mouseY, partialTick);
	}

	private void animate() {
		if (models.isEmpty()) {
			return;
		}

		float breath = MathHelper.sin(elapsed / BREATH_PERIOD * (MathHelper.PI * 2.0f));
		float sway = MathHelper.sin(elapsed / SWAY_PERIOD * (MathHelper.PI * 2.0f));
		float lift = breath * BODY_LIFT;

		for (PlayerEntityModel model : models) {
			// Everything above the feet rises together, so the torso never
			// detaches from the legs. The legs stay planted.
			liftFrom(model.head, lift);
			liftFrom(model.hat, lift);
			liftFrom(model.body, lift);
			liftFrom(model.jacket, lift);
			liftFrom(model.rightArm, lift);
			liftFrom(model.leftArm, lift);
			liftFrom(model.rightSleeve, lift);
			liftFrom(model.leftSleeve, lift);
			liftFrom(model.rightLeg, 0.0f);
			liftFrom(model.leftLeg, 0.0f);
			liftFrom(model.rightPants, 0.0f);
			liftFrom(model.leftPants, 0.0f);

			// Arms drift away from the torso and back, mirrored left to right.
			float swing = ARM_SWING + sway * ARM_SWING * 0.6f;
			float pitch = sway * ARM_PITCH;

			pose(model.rightArm, pitch, swing);
			pose(model.leftArm, pitch, -swing);
			pose(model.rightSleeve, pitch, swing);
			pose(model.leftSleeve, pitch, -swing);
		}
	}

	/** Moves a part relative to its captured rest position. */
	private void liftFrom(ModelPart part, float offset) {
		Float rest = restY.get(part);
		if (rest != null) {
			part.originY = rest + offset;
		}
	}

	private static void pose(ModelPart part, float xRot, float zRot) {
		part.pitch = xRot;
		part.roll = zRot;
	}
}
