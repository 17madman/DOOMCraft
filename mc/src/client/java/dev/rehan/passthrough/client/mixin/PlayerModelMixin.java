package dev.rehan.passthrough.client.mixin;

import dev.rehan.passthrough.client.Mimic;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Steve's limbs as the host's character holds its own (see Mimic). */
@Mixin(PlayerModel.class)
abstract class PlayerModelMixin {
	@Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"))
	private void passthrough$mimic(final AvatarRenderState state, final CallbackInfo ci) {
		Mimic.apply((PlayerModel) (Object) this);
	}
}
