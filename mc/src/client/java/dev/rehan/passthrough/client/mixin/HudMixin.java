package dev.rehan.passthrough.client.mixin;

import dev.rehan.passthrough.Vitals;
import dev.rehan.passthrough.client.Doll;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While the host's own menu is up the HUD isn't drawn (see Doll). */
@Mixin(Hud.class)
abstract class HudMixin {
	@Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
	private void passthrough$doll(final GuiGraphicsExtractor graphics, final DeltaTracker deltaTracker, final CallbackInfo ci) {
		if (Doll.extract(graphics)) {
			ci.cancel();
		}
	}

	/** No hunger under this host (see Vitals): no food bar either. */
	@Inject(method = "extractFood", at = @At("HEAD"), cancellable = true)
	private void passthrough$noFoodBar(final GuiGraphicsExtractor graphics, final Player player, final int x, final int y, final CallbackInfo ci) {
		if (Vitals.hungerOff()) {
			ci.cancel();
		}
	}
}
