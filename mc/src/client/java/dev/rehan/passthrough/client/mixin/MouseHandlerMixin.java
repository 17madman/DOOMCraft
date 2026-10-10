package dev.rehan.passthrough.client.mixin;

import dev.rehan.passthrough.Passthrough;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The host's window has the mouse, so Minecraft's is never grabbed, and Minecraft only keeps breaking a block while
 * the attack key is held if it is. In survival that is all of mining: under a host the mouse counts as grabbed
 * whenever no screen is open.
 */
@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {
	@Inject(method = "isMouseGrabbed()Z", at = @At("HEAD"), cancellable = true)
	private void passthrough$hostHasTheMouse(final CallbackInfoReturnable<Boolean> cir) {
		if (Passthrough.active && Minecraft.getInstance().gui.screen() == null) {
			cir.setReturnValue(true);
		}
	}
}
