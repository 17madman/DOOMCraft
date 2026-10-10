package dev.rehan.passthrough.mixin;

import dev.rehan.passthrough.Passthrough;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The host moves the player, so neither side should collide it with blocks: host terrain arrives as barriers
 * that the player can end up inside, and the server would otherwise reject those moves.
 */
@Mixin(Player.class)
abstract class PlayerMixin {
	@Inject(method = "tick", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/player/Player;noPhysics:Z", opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER))
	private void passthrough$ghost(final CallbackInfo ci) {
		Entity self = (Entity) (Object) this;
		// (the server's copy stays a ghost: it only follows the client, and must not argue with its moves)
		if (Passthrough.active && !(Passthrough.walk && self.level().isClientSide())) {
			self.noPhysics = true;
		}
	}

	/**
	 * Minecraft digs at a fifth of the speed when the player isn't standing on the ground. While the host moves him
	 * he is put where the host's player is, and Minecraft never has him "on the ground": every block took five
	 * times as long as it should. He counts as standing, for digging, whenever the host is the one moving him.
	 */
	@Redirect(method = "getDestroySpeed", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;onGround()Z"))
	private boolean passthrough$digsAsIfStanding(final Player player) {
		return player.onGround() || Passthrough.active && !Passthrough.walk;
	}
}
