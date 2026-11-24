package dev.spog.tiers.client.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.PlayerSkinWidget;
import net.minecraft.client.render.entity.model.LoadedEntityModels;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.entity.player.PlayerSkinType;
import net.minecraft.util.math.MathHelper;
import net.minecraft.entity.player.SkinTextures;
import org.joml.Matrix3x2f;
import org.joml.Vector2f;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
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
 * parts here just before the widget renders.
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

	private final Supplier<SkinTextures> skin;

	/**
	 * Vanilla's two models, wide then slim, and its two rotations, x then y.
	 *
	 * <p>Found by type and declaration order rather than by name: this
	 * version runs obfuscated, so a field's name in the code is not its name
	 * in the game. Empty when vanilla no longer has exactly two of each, and
	 * the widget then leaves drawing to vanilla.
	 */
	private static final List<Field> MODELS = fields(PlayerEntityModel.class);
	private static final List<Field> ROTATIONS = fields(float.class);

	public AnimatedSkinWidget(int width, int height, LoadedEntityModels models, Supplier<SkinTextures> skin) {
		super(width, height, models, skin);
		this.skin = skin;
		collectModels();
	}

	/**
	 * The parts of one model the idle animation moves.
	 *
	 * <p>Only the six that hang off the root: the hat, jacket, sleeves and
	 * trousers are children of these, so they follow without being moved
	 * themselves. Moving them as well doubled their lift and swing.
	 */
	private record Rig(ModelPart head, ModelPart body, ModelPart rightArm,
			ModelPart leftArm, ModelPart rightLeg, ModelPart leftLeg) {
		ModelPart[] all() {
			return new ModelPart[] {head, body, rightArm, leftArm, rightLeg, leftLeg};
		}
	}

	/**
	 * Grabs the widget's own wide and slim models. They are private with no
	 * accessor, and mixing in to vanilla for a cosmetic idle is not worth the
	 * porting cost, so this reads them reflectively and simply does nothing if
	 * the fields ever move.
	 *
	 * <p>Parts do not all rest at y=0 -- arms and legs sit lower down the rig
	 * -- so their rest positions are captured once and everything is animated
	 * as an offset from those.
	 */
	private void collectModels() {
		for (Field field : MODELS) {
			try {
				if (field.get(this) instanceof PlayerEntityModel model) {
					Rig rig = new Rig(model.head, model.body, model.rightArm,
							model.leftArm, model.rightLeg, model.leftLeg);
					rigs.add(rig);
					for (ModelPart part : rig.all()) {
						restY.putIfAbsent(part, part.originY);
					}
				}
			} catch (ReflectiveOperationException | RuntimeException ignored) {
				// Leave the widget static rather than failing to draw at all.
			}
		}
	}

	@Override
	protected void renderWidget(DrawContext graphics, int mouseX, int mouseY, float partialTick) {
		elapsed += partialTick / 20.0f;
		animate();
		if (!drawPosed(graphics)) {
			super.renderWidget(graphics, mouseX, mouseY, partialTick);
		}
	}

	/**
	 * Draws the model through the current matrices, as vanilla does not.
	 *
	 * <p>Vanilla hands the model's box and scale straight to the
	 * picture-in-picture renderer, which never sees the matrices. A screen
	 * drawn under a scale of its own -- the profile screen keeps a fixed size
	 * that way -- had the model land at its unscaled position and size: up
	 * and to the left of its box and too small for it, by however far the
	 * game's GUI scale was from the screen's. Small windows pick a smaller GUI
	 * scale, so that is where it showed.
	 *
	 * @return false when there is no transform to undo or vanilla's fields
	 *         could not be read, leaving the draw to vanilla
	 */
	private boolean drawPosed(DrawContext graphics) {
		Matrix3x2f pose = graphics.getMatrices();
		if (isIdentity(pose) || MODELS.size() != 2 || ROTATIONS.size() != 2
				|| getHeight() <= 0) {
			return false;
		}
		try {
			SkinTextures current = skin.get();
			PlayerEntityModel model = (PlayerEntityModel)
					MODELS.get(current.model() == PlayerSkinType.SLIM ? 1 : 0).get(this);
			Vector2f topLeft = pose.transformPosition(getX(), getY(), new Vector2f());
			Vector2f bottomRight = pose.transformPosition(getRight(), getBottom(), new Vector2f());
			float height = bottomRight.y - topLeft.y;
			// Vanilla's own fit, taken against the height the box really has.
			graphics.addPlayerSkin(model, current.body().texturePath(), 0.97f * height / 2.125f,
					ROTATIONS.get(0).getFloat(this), ROTATIONS.get(1).getFloat(this), -1.0625f,
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

	/** Vanilla's instance fields of one type, in the order it declares them. */
	private static List<Field> fields(Class<?> type) {
		List<Field> found = new ArrayList<>();
		try {
			for (Field field : PlayerSkinWidget.class.getDeclaredFields()) {
				if (field.getType() == type && !Modifier.isStatic(field.getModifiers())) {
					field.setAccessible(true);
					found.add(field);
				}
			}
		} catch (RuntimeException e) {
			return List.of();
		}
		return found.size() == 2 ? List.copyOf(found) : List.of();
	}

	private void animate() {
		if (rigs.isEmpty()) {
			return;
		}

		float breath = MathHelper.sin(elapsed / BREATH_PERIOD * (MathHelper.PI * 2.0f));
		float sway = MathHelper.sin(elapsed / SWAY_PERIOD * (MathHelper.PI * 2.0f));
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
			part.originY = rest + offset;
		}
	}

	private static void pose(ModelPart part, float xRot, float zRot) {
		part.pitch = xRot;
		part.roll = zRot;
	}
}
