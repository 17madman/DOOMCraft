package dev.rehan.passthrough.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.player.PlayerModel;

/**
 * Steve doing what the host's player is doing. Steve has none of the host character's animations, but he has the
 * same parts: a body, two arms, two legs. The host says which way its character's body leans and where each limb
 * points, relative to the body ("limbs" in the camera message), and Steve's are turned the same way: he lies down
 * when the host's player lies in bed, sits up, climbs, holds things up, swings, rides, falls over.
 *
 * <p>{@code "limbs":[lean, roll, rightArmX, rightArmZ, leftArmX, leftArmZ, rightLegX, rightLegZ, leftLegX, leftLegZ, twist]}
 * in degrees: lean (forward +) and roll (to his right +) of the whole body, for each limb the two rotations a
 * Minecraft model part takes (x: swing forward is negative; z: out to his right is positive), and last the turn of
 * the body about its own spine (which way the chest faces when he lies). The host project's tools/check_mimic.py
 * checks its numbers against what is done with them here.
 */
public final class Mimic {
	private static final int N = 11;
	private static float[] target;
	private static final float[] shown = new float[N];
	private static boolean active;
	private static long last;

	private Mimic() {
	}

	/** Every frame (render thread): what to show this frame, eased towards what the host last said. */
	static void frame(final HostState.Pose p) {
		float[] limbs = p == null || p.firstPerson() || p.walk() ? null : p.limbs();
		long now = System.nanoTime();
		float dt = Math.min(0.1F, (now - last) / 1.0e9F);
		last = now;
		if (limbs == null || limbs.length < N) {
			active = false;
			target = null;
			return;
		}

		// the host's character moves 20 times a second: eased, Steve doesn't tick from pose to pose
		float ease = active && target != null ? 1.0F - (float) Math.exp(-dt * 22.0F) : 1.0F;
		for (int i = 0; i < N; i++) {
			float step = limbs[i] - shown[i];
			step -= 360.0F * (float) Math.floor((step + 180.0F) / 360.0F);
			shown[i] += step * ease;
		}

		target = limbs;
		active = true;
	}

	public static boolean active() {
		return active;
	}

	/** After the model has been posed the usual way: its limbs as the host's character holds them. */
	public static void apply(final PlayerModel model) {
		if (!active) {
			return;
		}

		float rad = (float) (Math.PI / 180.0);
		model.body.xRot = 0.0F;
		model.body.yRot = 0.0F;
		model.body.zRot = 0.0F;
		model.rightArm.xRot = shown[2] * rad;
		model.rightArm.yRot = 0.0F;
		model.rightArm.zRot = shown[3] * rad;
		model.leftArm.xRot = shown[4] * rad;
		model.leftArm.yRot = 0.0F;
		model.leftArm.zRot = shown[5] * rad;
		model.rightLeg.xRot = shown[6] * rad;
		model.rightLeg.yRot = 0.0F;
		model.rightLeg.zRot = shown[7] * rad;
		model.leftLeg.xRot = shown[8] * rad;
		model.leftLeg.yRot = 0.0F;
		model.leftLeg.zRot = shown[9] * rad;
	}

	/**
	 * After the renderer has turned him to face his way: the whole of him leaned as the host's character's body is.
	 * (In this space he faces -z and his right is +x: lying prone, as a swimmer, is -90 degrees about x.)
	 */
	public static void tilt(final PoseStack pose) {
		if (!active) {
			return;
		}

		if (Math.abs(shown[0]) > 0.5F) {
			pose.mulPose(new org.joml.Matrix4f().rotate(Axis.XP.rotationDegrees(-shown[0])));
		}

		if (Math.abs(shown[1]) > 0.5F) {
			pose.mulPose(new org.joml.Matrix4f().rotate(Axis.ZP.rotationDegrees(-shown[1])));
		}

		if (Math.abs(shown[10]) > 0.5F) {
			pose.mulPose(new org.joml.Matrix4f().rotate(Axis.YP.rotationDegrees(shown[10])));
		}
	}
}
