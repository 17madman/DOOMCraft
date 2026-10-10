package dev.rehan.passthrough.client;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import dev.rehan.passthrough.Passthrough;
import java.util.Locale;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.phys.Vec3;

/** Keeps the Minecraft player on the host's player: it stands where they stand and looks where the host camera looks. */
public final class PlayerSync {
	private static final double TELEPORT_SQ = 64.0 * 64.0;
	/** How far the host's player moved over the last client tick (drives the walk animation). */
	private static float tickDistance;
	private static double lastX = Double.NaN, lastZ;
	private static boolean wasWalking;
	private static Vec3 waitAt;
	private static int walkTicks;
	private static boolean steppedUp;

	private PlayerSync() {
	}

	public static float tickDistance() {
		return tickDistance;
	}

	private static int lastSwing = -1;
	private static boolean wasHurt;
	private static long deadSince;

	/**
	 * What the host's player is doing, shown the way Steve can: dying (he falls over, as a dead player does), a
	 * flinch when hurt, a swing of the arm, crouching, swimming, sitting.
	 */
	private static void act(final LocalPlayer player, final HostState.Pose p) {
		if (p.pose().isEmpty()) {
			return;
		}

		// (when the host says how its character holds itself, Steve does that: he falls as it falls)
		boolean dead = p.pose().equals("dead") && !Mimic.active();
		if (dead) {
			if (deadSince == 0L) {
				deadSince = System.nanoTime();
			}

			player.deathTime = (int) Math.min(20L, (System.nanoTime() - deadSince) / 50_000_000L);
		} else if (deadSince != 0L) {
			deadSince = 0L;
			player.deathTime = 0;
		}

		if (p.hurt() && !wasHurt) {
			player.hurtTime = player.hurtDuration = 10;
		}

		wasHurt = p.hurt();
		if (p.swing() >= 0 && lastSwing >= 0 && p.swing() != lastSwing) {
			player.swing(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
		}

		lastSwing = p.swing();
		if (!p.walk()) {
			// (walking, the pose is Minecraft's own: sneaking, gliding)
			net.minecraft.world.entity.Pose pose = Mimic.active() ? net.minecraft.world.entity.Pose.STANDING : switch (p.pose()) {
				case "crouch" -> net.minecraft.world.entity.Pose.CROUCHING;
				case "present" -> net.minecraft.world.entity.Pose.STANDING;
				case "swim" -> net.minecraft.world.entity.Pose.SWIMMING;
				case "sit" -> net.minecraft.world.entity.Pose.SITTING;
				default -> net.minecraft.world.entity.Pose.STANDING;
			};
			if (player.getPose() != pose) {
				player.setPose(pose);
			}
		}
	}

	/** Every frame, before the camera update: position, rotation, and first/third person to match the host. */
	public static void frame(final float partialTick) {
		HostState.Pose p = HostState.frame();
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		if (p == null || player == null) {
			// No host yet: the world is empty, there is nothing under the player. He waits where he is instead of
			// falling out of it (and dying, over and over, until a host comes).
			if (player != null && PassthroughClient.ownWorld()) {
				if (waitAt == null) {
					waitAt = player.position();
				}

				player.setPos(waitAt);
				player.setDeltaMovement(Vec3.ZERO);
				player.fallDistance = 0.0;
			}

			return;
		}

		waitAt = null;
		Mimic.frame(p);
		act(player, p);
		if (p.drive() || p.walk()) {
			// Minecraft moves the player (elytra flight, or walking): it only takes where to look, and tells the host where it is
			player.setYRot(p.lookYaw());
			player.setXRot(p.lookPitch());
			player.yRotO = p.lookYaw();
			player.xRotO = p.lookPitch();
			player.yHeadRot = player.yHeadRotO = p.lookYaw();
			CameraType type = p.walk() && p.firstPerson() ? CameraType.FIRST_PERSON : CameraType.THIRD_PERSON_BACK;
			if (minecraft.options.getCameraType() != type) {
				minecraft.options.setCameraType(type);
			}
			if (p.drive()) {
				// airborne: with no collision onGround never updates, and the server cancels a grounded player's glide
				player.setOnGround(false);
			}

			// where the player is drawn this frame, how fast that moves (the slope of the tick interpolation, per
			// second) and when (System.nanoTime is the host's QueryPerformanceCounter clock): the host carries it forward
			Vec3 at = player.getPosition(partialTick);
			double vx = (player.getX() - player.xo) * 20.0, vy = (player.getY() - player.yo) * 20.0, vz = (player.getZ() - player.zo) * 20.0;
			Passthrough.events.accept(String.format(Locale.ROOT, "{\"t\":\"mcpos\",\"pos\":[%.4f,%.4f,%.4f],\"vel\":[%.3f,%.3f,%.3f],\"tn\":%d,\"fly\":%b,\"ground\":%b,\"yaw\":%.2f}",
				at.x, at.y, at.z, vx, vy, vz, System.nanoTime(), player.isFallFlying(), player.onGround(), player.yBodyRot));
			return;
		}

		// the model stands exactly where the host's player is this frame (not a tick behind, interpolating)
		double x = p.firstPerson() ? p.x() : p.px();
		double y = p.firstPerson() ? p.y() - player.getEyeHeight() : p.py();
		double z = p.firstPerson() ? p.z() : p.pz();
		float yaw = p.yaw(), pitch = p.pitch();
		if (!p.firstPerson() && p.ray() != null) {
			// a fixed camera with a cursor: Steve looks at the spot under it, a reach past himself along the ray
			double[] d = p.ray();
			double ey = y + player.getEyeHeight();
			double along = Math.max(0.0, (x - p.x()) * d[0] + (ey - p.y()) * d[1] + (z - p.z()) * d[2]) + 3.0;
			double lx = p.x() + d[0] * along - x, ly = p.y() + d[1] * along - ey, lz = p.z() + d[2] * along - z;
			yaw = (float) Math.toDegrees(Math.atan2(-lx, lz));
			pitch = (float) -Math.toDegrees(Math.atan2(ly, Math.hypot(lx, lz)));
		} else if (!p.firstPerson() && p.ownLook()) {
			yaw = p.lookYaw();
			pitch = p.lookPitch();
		}

		player.setYRot(yaw);
		player.setXRot(pitch);
		player.yRotO = yaw;
		player.xRotO = pitch;
		float body = p.firstPerson() ? yaw : p.bodyYaw();
		// the head turns as far as a neck does; past that Steve still aims there, he just doesn't show it
		float turn = yaw - body;
		turn -= 360.0F * (float) Math.floor((turn + 180.0F) / 360.0F);
		player.yHeadRot = player.yHeadRotO = body + Math.clamp(turn, -80.0F, 80.0F);
		player.yBodyRot = player.yBodyRotO = body;
		player.setPos(x, y, z);
		player.xo = player.xOld = x;
		player.yo = player.yOld = y;
		player.zo = player.zOld = z;
		CameraType cameraType = p.firstPerson() ? CameraType.FIRST_PERSON : CameraType.THIRD_PERSON_BACK;
		if (minecraft.options.getCameraType() != cameraType) {
			minecraft.options.setCameraType(cameraType);
		}
	}

	/**
	 * Every client tick, at the start of the player's tick (the old position is already saved, so the model
	 * interpolates and walks): in first person the player's eyes are at the host camera, in third person
	 * their feet are at the host player's.
	 */
	public static void tick(final LocalPlayer player) {
		HostState.Pose p = HostState.live();
		if (p != null && p.walk()) {
			// on foot under Minecraft's own rules: no hovering, and no double-tap flight either
			Abilities walking = player.getAbilities();
			if (walking.flying || walking.mayfly) {
				walking.flying = false;
				walking.mayfly = false;
				wasWalking = true;
			}

			// how high a step he walks up without jumping, if the host says (its own player's)
			var stepHeight = player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.STEP_HEIGHT);
			if (stepHeight != null && p.step() > 0.0F && stepHeight.getBaseValue() != p.step()) {
				stepHeight.setBaseValue(p.step());
				steppedUp = true;
			}

			double x = player.getX(), y = player.getY(), z = player.getZ();
			double ground = dev.rehan.passthrough.Ground.at(x, z);
			boolean under = false;
			if (Passthrough.heightField) {
				// His movement is collided with the host's ground in Entity.collide (EntityCollideMixin). This is the
				// last resort under that: whatever happens, he is never left under the ground.
				under = !Double.isNaN(ground) && y < ground - 0.05 && ground - y < 4.0;
				if (under) {
					player.setPos(x, ground, z);
					player.setDeltaMovement(player.getDeltaMovement().multiply(1.0, 0.0, 1.0));
					player.setOnGround(true);
				}
			} else if (!player.isFallFlying() && !player.level().noCollision(player)) {
				// Blocks: the host's floors are rounded to an eighth of a block, lifts rise under him, and Minecraft
				// doesn't stop something that is already inside a block from sinking through it: he is lifted out.
				for (int i = 1; i <= 24 && !under; i++) {
					player.setPos(x, y + i * 0.0625, z);
					under = player.level().noCollision(player);
				}

				if (!under) {
					player.setPos(x, y, z); // inside a wall, not a floor: Minecraft's own pushing out deals with it
				} else if (player.getDeltaMovement().y < 0.0) {
					player.setDeltaMovement(player.getDeltaMovement().multiply(1.0, 0.0, 1.0));
				}
			}

			if (under || walkTicks++ % 100 == 0) {
				Passthrough.LOG.info("walk: at {} {} {}, ground {} ({} columns), onGround {}, noPhysics {}, gliding {}{}",
					String.format(Locale.ROOT, "%.2f", x), String.format(Locale.ROOT, "%.2f", y), String.format(Locale.ROOT, "%.2f", z),
					String.format(Locale.ROOT, "%.2f", ground), dev.rehan.passthrough.Ground.size(), player.onGround(), player.noPhysics,
					player.isFallFlying(), under ? " -- was under the ground, put back on it" : "");
			}

			tickDistance = 0.0F;
			lastX = Double.NaN;
			return;
		}

		if (wasWalking) {
			wasWalking = false;
			player.getAbilities().mayfly = true;
		}

		if (steppedUp) {
			steppedUp = false;
			var stepHeight = player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.STEP_HEIGHT);
			if (stepHeight != null) {
				stepHeight.setBaseValue(0.6);
			}
		}

		if (p == null || p.drive()) {
			return;
		}

		double x = p.firstPerson() ? p.x() : p.px();
		double y = p.firstPerson() ? p.y() - player.getEyeHeight() : p.py();
		double z = p.firstPerson() ? p.z() : p.pz();
		tickDistance = Double.isNaN(lastX) ? 0.0F : (float) Math.min(Math.hypot(x - lastX, z - lastZ), 1.0);
		lastX = x;
		lastZ = z;
		boolean teleport = player.distanceToSqr(x, y, z) > TELEPORT_SQ;
		player.setPos(x, y, z);
		if (teleport) {
			player.xo = player.xOld = x;
			player.yo = player.yOld = y;
			player.zo = player.zOld = z;
		}

		player.setDeltaMovement(Vec3.ZERO);
		Abilities abilities = player.getAbilities();
		if (abilities.mayfly && !abilities.flying) {
			abilities.flying = true;
			player.onUpdateAbilities();
		}
	}
}
