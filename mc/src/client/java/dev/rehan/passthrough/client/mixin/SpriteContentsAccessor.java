package dev.rehan.passthrough.client.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A block texture's pixels, for the cubes the host draws itself (BlockExport). */
@Mixin(SpriteContents.class)
public interface SpriteContentsAccessor {
	@Accessor("originalImage")
	NativeImage passthrough$originalImage();
}
