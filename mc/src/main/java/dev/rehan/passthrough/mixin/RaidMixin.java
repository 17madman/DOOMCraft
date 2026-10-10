package dev.rehan.passthrough.mixin;

import dev.rehan.passthrough.Ground;
import dev.rehan.passthrough.Passthrough;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.raid.Raid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Under a host a raid finds its raiders somewhere to appear on the host's ground. Minecraft looks for the top block
 * of its own world around the village and wants ground there that mobs spawn on; the host's map is not blocks of
 * that kind (and has roofs over it), so it never found a place and the raid stood at its first wave with nobody in it.
 */
@Mixin(Raid.class)
abstract class RaidMixin {
	@Inject(method = "findRandomSpawnPos", at = @At("HEAD"), cancellable = true)
	private void passthrough$onHostGround(final ServerLevel level, final int attempts, final CallbackInfoReturnable<BlockPos> cir) {
		if (!Passthrough.active) {
			return;
		}

		BlockPos center = ((Raid) (Object) this).getCenter();
		for (int i = 0; i < 48; i++) {
			// nearer than Minecraft has them come from (it is 32 to 64 blocks there): the host only describes the
			// ground about the player, and a map's rooms are small
			double angle = level.getRandom().nextFloat() * (float) (Math.PI * 2.0);
			double far = i < 40 ? 8.0 + level.getRandom().nextInt(16) : 2.0 + level.getRandom().nextInt(5);
			int x = center.getX() + (int) Math.floor(Math.cos(angle) * far), z = center.getZ() + (int) Math.floor(Math.sin(angle) * far);
			double ground = Ground.at(x + 0.5, z + 0.5);
			if (!Double.isNaN(ground) && Math.abs(ground - center.getY()) < 6.0) {
				cir.setReturnValue(new BlockPos(x, (int) Math.ceil(ground), z));
				return;
			}
		}

		cir.setReturnValue(center);
	}
}
