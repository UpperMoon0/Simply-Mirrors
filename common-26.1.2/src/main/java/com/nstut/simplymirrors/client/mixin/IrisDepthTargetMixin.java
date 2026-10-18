package com.nstut.simplymirrors.client.mixin;
import org.spongepowered.asm.mixin.Mixin;
import com.mojang.blaze3d.textures.GpuTexture;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** Separate render targets may have identical allocation-version counters. */
@Pseudo
@Mixin(targets="net.irisshaders.iris.targets.RenderTargets",remap=false)
public abstract class IrisDepthTargetMixin {
    @Shadow private GpuTexture currentDepthTexture;
    @Shadow private int cachedDepthBufferVersion;
    @Inject(method="resizeIfNeeded",at=@At("HEAD"))
    private void simplymirrors$depthIdentity(int version,GpuTexture texture,int width,int height,
            @Coerce Object format,@Coerce Object directives,CallbackInfoReturnable<Boolean> ci) {
        // Let Iris perform its own complete attachment update when switching
        // between the screen and mirror, even if both counters equal one.
        if(currentDepthTexture!=texture)cachedDepthBufferVersion=version^Integer.MIN_VALUE;
    }
}
