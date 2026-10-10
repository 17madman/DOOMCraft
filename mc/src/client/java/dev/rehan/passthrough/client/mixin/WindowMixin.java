package dev.rehan.passthrough.client.mixin;

import com.mojang.blaze3d.platform.Window;
import dev.rehan.passthrough.Passthrough;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Under a host the window is hidden (the host shows Minecraft's picture) or sits behind the host's. Minecraft would
 * take either for "minimized" and all but stop drawing; the host needs every frame.
 */
@Mixin(Window.class)
abstract class WindowMixin {
	@Inject(method = "isIconified", at = @At("HEAD"), cancellable = true)
	private void passthrough$notIconified(final CallbackInfoReturnable<Boolean> cir) {
		if (Passthrough.active) {
			cir.setReturnValue(false);
		}
	}
}
