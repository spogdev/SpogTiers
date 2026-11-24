package dev.spog.tiers.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerSkinWidget;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import org.joml.Matrix3x2f;
import org.joml.Vector2f;

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
	/** Rest y of every animated part, so offsets never accumulate. */
	private final Map<ModelPart, Float> restY = new HashMap<>();

	private float elapsed;

	private final Supplier<PlayerSkin> skin;

	/**
	 * Vanilla's own fields, read to draw the model the way it would. Null
	 * when a field could not be found, and the widget then leaves drawing to
	 * vanilla.
	 */
	private static final Field WIDE_MODEL = field("wideModel");
	private static final Field SLIM_MODEL = field("slimModel");
	private static final Field ROTATION_X = field("rotationX");
	private static final Field ROTATION_Y = field("rotationY");

	public AnimatedSkinWidget(int width, int height, EntityModelSet models, Supplier<PlayerSkin> skin) {
		super(width, height, models, skin);
		this.skin = skin;
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
				if (field.get(this) instanceof PlayerModel model) {
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
	private void rememberRest(PlayerModel model) {
		for (ModelPart part : parts(model)) {
			restY.putIfAbsent(part, part.y);
		}
	}

	private static ModelPart[] parts(PlayerModel model) {
		return new ModelPart[] {
			model.head, model.hat, model.body, model.jacket,
			model.rightArm, model.leftArm, model.rightSleeve, model.leftSleeve,
			model.rightLeg, model.leftLeg, model.rightPants, model.leftPants,
		};
	}

	@Override
	public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		elapsed += partialTick / 20.0f;
		animate();
		if (!drawPosed(graphics)) {
			super.extractWidgetRenderState(graphics, mouseX, mouseY, partialTick);
		}
	}

	/**
	 * Draws the model through the current pose, as vanilla does not.
	 *
	 * <p>Vanilla hands the model's box and scale straight to the
	 * picture-in-picture renderer, which never sees the pose. A screen drawn
	 * under a scale of its own -- the profile screen keeps a fixed size that
	 * way -- had the model land at its unscaled position and size: up and to
	 * the left of its box and too small for it, by however far the game's GUI
	 * scale was from the screen's. Small windows pick a smaller GUI scale, so
	 * that is where it showed.
	 *
	 * @return false when there is no pose to undo or vanilla's fields could
	 *         not be read, leaving the draw to vanilla
	 */
	private boolean drawPosed(GuiGraphicsExtractor graphics) {
		Matrix3x2f pose = graphics.pose();
		if (isIdentity(pose) || WIDE_MODEL == null || SLIM_MODEL == null
				|| ROTATION_X == null || ROTATION_Y == null || getHeight() <= 0) {
			return false;
		}
		try {
			PlayerSkin current = skin.get();
			Model.Simple model = (Model.Simple) (current.model() == PlayerModelType.SLIM
					? SLIM_MODEL.get(this) : WIDE_MODEL.get(this));
			Vector2f topLeft = pose.transformPosition(getX(), getY(), new Vector2f());
			Vector2f bottomRight = pose.transformPosition(getRight(), getBottom(), new Vector2f());
			float height = bottomRight.y - topLeft.y;
			// Vanilla's own fit, taken against the height the box really has.
			graphics.skin(model, current.body().texturePath(), 0.97f * height / 2.125f,
					ROTATION_X.getFloat(this), ROTATION_Y.getFloat(this), -1.0625f,
					Math.round(topLeft.x), Math.round(topLeft.y),
					Math.round(bottomRight.x), Math.round(bottomRight.y));
			return true;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return false;
		}
	}

	/** No scale and no shift, so vanilla's own draw lands where it should. */
	private static boolean isIdentity(Matrix3x2f pose) {
		return pose.m00() == 1.0f && pose.m11() == 1.0f && pose.m01() == 0.0f
				&& pose.m10() == 0.0f && pose.m20() == 0.0f && pose.m21() == 0.0f;
	}

	private static Field field(String name) {
		try {
			Field field = PlayerSkinWidget.class.getDeclaredField(name);
			field.setAccessible(true);
			return field;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	private void animate() {
		if (models.isEmpty()) {
			return;
		}

		float breath = Mth.sin(elapsed / BREATH_PERIOD * Mth.TWO_PI);
		float sway = Mth.sin(elapsed / SWAY_PERIOD * Mth.TWO_PI);
		float lift = breath * BODY_LIFT;

		for (PlayerModel model : models) {
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
			part.y = rest + offset;
		}
	}

	private static void pose(ModelPart part, float xRot, float zRot) {
		part.xRot = xRot;
		part.zRot = zRot;
	}
}
