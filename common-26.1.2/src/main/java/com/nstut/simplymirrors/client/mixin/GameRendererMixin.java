package com.nstut.simplymirrors.client.mixin;

import com.nstut.simplymirrors.client.MirrorRenderer;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    // Capture the actual world projection, including view bobbing, hurt tilt, and nausea.
    @ModifyArg(method="renderLevel", at=@At(value="INVOKE", target="Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;", ordinal=0), index=0)
    private Matrix4f simplymirrors$captureProjection(Matrix4f projection) {
        MirrorRenderer.mainProjection.set(projection);
        return projection;
    }
}
