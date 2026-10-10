package dev.rehan.passthrough.client.mixin;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import dev.rehan.passthrough.Passthrough;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Under a host Minecraft keeps a steady 100 frames a second whatever its window is doing: enough to have a fresh
 * frame for each of the host's, without taking the GPU from the host (flat out it reads back 260 frames a second,
 * and the host stutters). Its own caps, for a window out of focus or idle, are for a game someone is looking at.
 */
@Mixin(FramerateLimitTracker.class)
abstract class FramerateLimitTrackerMixin {
	@Inject(method = "getFramerateLimit", at = @At("HEAD"), cancellable = true)
	private void passthrough$unlimited(final CallbackInfoReturnable<Integer> cir) {
		if (Passthrough.active) {
			cir.setReturnValue(165);
		}
	}
}
