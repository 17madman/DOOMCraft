package dev.rehan.passthrough.client.mixin;

import dev.rehan.passthrough.client.HostState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Holding one of the host's guns (drawn by the host): Steve aims with both arms forward, and holds nothing of his own. */
@Mixin(AvatarRenderer.class)
abstract class AvatarRendererMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V", at = @At("TAIL"))
	private void passthrough$gunPose(final Avatar entity, final AvatarRenderState state, final float partialTick, final CallbackInfo ci) {
		HostState.Pose p = HostState.frame();
		if (p == null || entity != Minecraft.getInstance().player) {
			return;
		}

		// Minecraft walks him, a little ahead of where the host has caught up to: he is drawn where the host's player
		// is, which moves evenly with the host's camera
		if (p.walk() && !p.firstPerson()) {
			state.x = p.px();
			state.y = p.py();
			state.z = p.pz();
		}

		// the host's player is riding: Steve sits, as he does on a horse of his own
		if ("sit".equals(p.pose()) && !dev.rehan.passthrough.client.Mimic.active()) {
			state.isPassenger = true;
		}

		// he holds up what the host has just given him (the host draws the thing itself): both arms straight up,
		// empty-handed
		if ("present".equals(p.pose())) {
			if (!dev.rehan.passthrough.client.Mimic.active()) {
				state.rightArmPose = HumanoidModel.ArmPose.THROW_TRIDENT;
				state.leftArmPose = HumanoidModel.ArmPose.THROW_TRIDENT;
			}

			state.rightHandItemState.clear();
			state.leftHandItemState.clear();
			state.rightHandItemStack = ItemStack.EMPTY;
			state.leftHandItemStack = ItemStack.EMPTY;
		}

		if (!p.gun()) {
			return;
		}

		state.rightArmPose = HumanoidModel.ArmPose.CROSSBOW_HOLD;
		state.leftArmPose = HumanoidModel.ArmPose.EMPTY;
		state.rightHandItemState.clear();
		state.leftHandItemState.clear();
		state.rightHandItemStack = ItemStack.EMPTY;
		state.leftHandItemStack = ItemStack.EMPTY;
	}

	/** The whole of him leaned as the host's character's body is (lying, sitting up, falling): see Mimic. */
	@Inject(method = "setupRotations(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;FF)V", at = @At("TAIL"))
	private void passthrough$lean(final AvatarRenderState state, final com.mojang.blaze3d.vertex.PoseStack pose, final float bodyRot,
		final float scale, final CallbackInfo ci) {
		dev.rehan.passthrough.client.Mimic.tilt(pose);
	}
}
