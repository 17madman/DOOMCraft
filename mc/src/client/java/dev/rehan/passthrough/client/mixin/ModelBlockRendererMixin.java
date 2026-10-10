package dev.rehan.passthrough.client.mixin;

import dev.rehan.passthrough.WorldBridge;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The host's ground around a hole the player has dug shows as ordinary blocks (the walls of the hole). Of those
 * blocks only the faces that look into the hole are drawn: the others are the host's own ground, which the host
 * draws. (Drawn whole, each hole had a frame of dirt squares laid over the grass around it, and on a slope blocks
 * of dirt standing out of the ground.)
 */
@Mixin(ModelBlockRenderer.class)
abstract class ModelBlockRendererMixin {
	@Inject(method = "shouldRenderFace", at = @At("HEAD"), cancellable = true)
	private void passthrough$wallsOfTheHoleOnly(final BlockAndTintGetter level, final BlockState state, final Direction direction, final BlockPos neighbour,
		final CallbackInfoReturnable<Boolean> cir) {
		if (WorldBridge.anyLining() && !WorldBridge.minedCell(neighbour) && WorldBridge.isLining(neighbour.relative(direction.getOpposite()))) {
			cir.setReturnValue(false);
		}
	}
}
