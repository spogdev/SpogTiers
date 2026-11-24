package dev.spog.tiers.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerSkinWidget;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelPart;
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
 * <p>Vanilla keeps its two models private, so the pose is applied to their
 * parts here just before the widget extracts its own render state.
 */
public class AnimatedSkinWidget extends PlayerSkinWidget {
	/** Seconds for one full breath in and out. */
	private static final float BREATH_PERIOD = 4.0f;
	/** Seconds for one full arm sway, deliberately offset from the breath. */
	private static final float SWAY_PERIOD = 5.5f;

	private static final float BODY_LIFT = 0.22f;
	private static final float ARM_SWING = 0.022f;
	private static final float ARM_PITCH = 0.018f;

	private final List<Rig> rigs = new ArrayList<>();
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
	 * The parts of one model the idle animation moves.
	 *
	 * <p>Only the six that hang off the root: the hat, jacket, sleeves and
	 * trousers are children of these, so they follow without being moved
	 * themselves.
	 */
	private record Rig(ModelPart head, ModelPart body, ModelPart rightArm,
			ModelPart leftArm, ModelPart rightLeg, ModelPart leftLeg) {
		ModelPart[] all() {
			return new ModelPart[] {head, body, rightArm, leftArm, rightLeg, leftLeg};
		}
	}

	/**
	 * Finds vanilla's two player models and the parts to animate in each.
	 *
	 * <p>Vanilla keeps them as plain {@link Model.Simple}s built from the
	 * player layer rather than as player models, so the parts are looked up
	 * by their layer names under each one's root. Looking for player-model
	 * fields instead found nothing, and the model stood still.
	 *
	 * <p>Read reflectively, since the fields have no accessor and mixing in to
	 * vanilla for a cosmetic idle is not worth the porting cost. If they ever
	 * move, the widget simply stands still.
	 */
	private void collectModels() {
		for (Field field : PlayerSkinWidget.class.getDeclaredFields()) {
			if (!Model.class.isAssignableFrom(field.getType())) {
				continue;
			}
			try {
				field.setAccessible(true);
				if (field.get(this) instanceof Model<?> model) {
					ModelPart root = model.root();
					Rig rig = new Rig(root.getChild("head"), root.getChild("body"),
							root.getChild("right_arm"), root.getChild("left_arm"),
							root.getChild("right_leg"), root.getChild("left_leg"));
					rigs.add(rig);
					for (ModelPart part : rig.all()) {
						restY.putIfAbsent(part, part.y);
					}
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
		if (rigs.isEmpty()) {
			return;
		}

		float breath = Mth.sin(elapsed / BREATH_PERIOD * Mth.TWO_PI);
		float sway = Mth.sin(elapsed / SWAY_PERIOD * Mth.TWO_PI);
		float lift = breath * BODY_LIFT;

		for (Rig rig : rigs) {
			// Everything above the feet rises together, so the torso never
			// detaches from the legs. The legs stay planted.
			liftFrom(rig.head(), lift);
			liftFrom(rig.body(), lift);
			liftFrom(rig.rightArm(), lift);
			liftFrom(rig.leftArm(), lift);
			liftFrom(rig.rightLeg(), 0.0f);
			liftFrom(rig.leftLeg(), 0.0f);

			// Arms drift away from the torso and back, mirrored left to right.
			// The sleeves are the arms' children and swing with them.
			float swing = ARM_SWING + sway * ARM_SWING * 0.6f;
			float pitch = sway * ARM_PITCH;

			pose(rig.rightArm(), pitch, swing);
			pose(rig.leftArm(), pitch, -swing);
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
