package dev.rehan.passthrough.client.mixin;

import dev.rehan.passthrough.Passthrough;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A world that is kept is opened again by itself at the next start, with nobody looking at Minecraft's window (it
 * hides behind the host). If Minecraft wants to ask whether to back the world up first, the answer is "go on":
 * otherwise it would wait on that question for ever.
 */
@Mixin(WorldOpenFlows.class)
abstract class WorldOpenFlowsMixin {
	@Inject(method = "askForBackup", at = @At("HEAD"), cancellable = true)
	private void passthrough$noQuestion(final LevelStorageSource.LevelStorageAccess access, final boolean oldCustomized, final Runnable proceed,
		final Runnable cancel, final CallbackInfo ci) {
		if (Passthrough.keepsWorld() && Passthrough.option("world", "passthrough").equals(access.getLevelId())) {
			proceed.run();
			ci.cancel();
		}
	}
}
