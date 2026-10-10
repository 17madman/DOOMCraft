package dev.rehan.passthrough.mixin;

import dev.rehan.passthrough.Vitals;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Under a host that says so there is no hunger: nothing uses food up, nothing starves and nothing heals by itself. */
@Mixin(FoodData.class)
abstract class FoodDataMixin {
	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void passthrough$noHunger(final ServerPlayer player, final CallbackInfo ci) {
		if (Vitals.hungerOff()) {
			FoodData food = (FoodData) (Object) this;
			if (food.getFoodLevel() != 20) {
				food.setFoodLevel(20);
			}

			ci.cancel();
		} else if (dev.rehan.passthrough.Passthrough.active && player.level().tickRateManager().isFrozen()) {
			// The host is paused (it has frozen the world): the player, whom the freeze leaves alone, neither gets
			// hungry nor heals from being fed meanwhile. (He healed all the way up behind the host's pause menu.)
			ci.cancel();
		}
	}
}
