package dev.rehan.passthrough.client.mixin;

import dev.rehan.passthrough.Ground;
import dev.rehan.passthrough.Passthrough;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While Minecraft walks the player: after his movement has been collided with Minecraft's blocks (his builds), it
 * is collided with the host's ground as a smooth height field (see Ground). Everything else is Minecraft's own:
 * gravity, jumping, stepping, being on the ground all follow from what comes back here.
 */
@Mixin(Entity.class)
abstract class EntityCollideMixin {
	@Inject(method = "collide", at = @At("RETURN"), cancellable = true)
	private void passthrough$hostGround(final Vec3 movement, final CallbackInfoReturnable<Vec3> cir) {
		if (Passthrough.walk && Passthrough.heightField && (Object) this instanceof LocalPlayer player && !player.noPhysics) {
			Vec3 m = cir.getReturnValue();
			double[] r = Ground.collide(player.getX(), player.getY(), player.getZ(), m.x, m.y, m.z, Math.max(player.maxUpStep(), 0.6F),
				!player.isFallFlying() && !player.getAbilities().flying);
			if (r != null) {
				cir.setReturnValue(new Vec3(r[0], r[1], r[2]));
			}
		} else if (Passthrough.walk && (Object) this instanceof LocalPlayer player && !player.noPhysics && !player.isFallFlying()) {
			// (among his own diggings he collides with blocks, not the height field: the host's walls hold all the same)
			Vec3 m = cir.getReturnValue();
			double[] held = dev.rehan.passthrough.Walls.hold(player.getX(), player.getZ(), m.x, m.z);
			if (held != null) {
				cir.setReturnValue(new Vec3(held[0], m.y, held[1]));
			}
		}
	}
}
