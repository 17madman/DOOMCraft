package dev.rehan.passthrough.client.mixin;

import dev.rehan.passthrough.client.BlockExport;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While the host draws the blocks itself (BlockExport), Minecraft's terrain stays out of its picture: there are no
 * visible sections for the moment the frame's chunk draws are put together. Entities, the hand, the block outline
 * and the cracks of mining are drawn as ever.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
	@Shadow
	@Final
	private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;
	@Shadow
	@Final
	private ObjectArrayList<SectionRenderDispatcher.RenderSection> nearbyVisibleSections;
	@Unique
	private final ObjectArrayList<SectionRenderDispatcher.RenderSection> passthrough$held = new ObjectArrayList<>();
	@Unique
	private final ObjectArrayList<SectionRenderDispatcher.RenderSection> passthrough$heldNearby = new ObjectArrayList<>();
	@Unique
	private boolean passthrough$holding;

	@Inject(method = {"prepareChunkRenders", "prepareChunkRendersIndirect"}, at = @At("HEAD"))
	private void passthrough$noTerrain(final Matrix4fc matrix, final boolean flag, final CallbackInfoReturnable<ChunkSectionsToRender> cir) {
		if (BlockExport.hostDraws() && !this.passthrough$holding) {
			this.passthrough$holding = true;
			this.passthrough$held.addAll(this.visibleSections);
			this.passthrough$heldNearby.addAll(this.nearbyVisibleSections);
			this.visibleSections.clear();
			this.nearbyVisibleSections.clear();
		}
	}

	@Inject(method = {"prepareChunkRenders", "prepareChunkRendersIndirect"}, at = @At("RETURN"))
	private void passthrough$terrainBack(final Matrix4fc matrix, final boolean flag, final CallbackInfoReturnable<ChunkSectionsToRender> cir) {
		if (this.passthrough$holding) {
			this.passthrough$holding = false;
			this.visibleSections.addAll(this.passthrough$held);
			this.nearbyVisibleSections.addAll(this.passthrough$heldNearby);
			this.passthrough$held.clear();
			this.passthrough$heldNearby.clear();
		}
	}

	/**
	 * The host's map is here as blocks that aren't drawn. Aimed at from above, the box around one marks the piece of
	 * ground that would be dug; aimed at from the side (a wall of the host's, the edge of its map) it was a black
	 * frame standing in the air around nothing. That one isn't drawn. (Only under a host whose map has such walls with
	 * nothing to see where they stand, which is the one that draws blocks itself: in Doom a wall's box lies on the wall.)
	 */
	@Inject(method = "submitBlockOutline", at = @At("HEAD"), cancellable = true)
	private void passthrough$noBoxInTheAir(final com.mojang.blaze3d.vertex.PoseStack pose, final net.minecraft.client.renderer.SubmitNodeCollector nodes,
		final net.minecraft.client.renderer.state.level.LevelRenderState state, final org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
		if (dev.rehan.passthrough.Passthrough.active && BlockExport.hostCan && minecraft.level != null
			&& minecraft.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
			net.minecraft.world.level.block.state.BlockState at = minecraft.level.getBlockState(hit.getBlockPos());
			boolean unseen = dev.rehan.passthrough.RockBlock.is(at) || dev.rehan.passthrough.GroundBlock.is(at);
			if (unseen && hit.getDirection() != net.minecraft.core.Direction.UP && !dev.rehan.passthrough.Materials.isCell(hit.getBlockPos())) {
				ci.cancel();
			}
		}
	}
}
