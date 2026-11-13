package com.nstut.simplymirrors.client.mixin;
import com.nstut.simplymirrors.client.ShaderReflection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** Separate render targets may have identical allocation-version counters. */
@Pseudo
@Mixin(targets={"net.irisshaders.iris.targets.RenderTargets","net.coderbot.iris.rendertarget.RenderTargets"},remap=false)
public abstract class IrisDepthTargetMixin {
    @Shadow private int currentDepthTexture;
    @Shadow private int cachedDepthBufferVersion;
    @Shadow private int cachedWidth;
    @Shadow private int cachedHeight;
    @Unique private boolean simplymirrors$historySetup;
    @Inject(method="resizeIfNeeded",at=@At("HEAD"))
    private void simplymirrors$depthIdentity(int version,int texture,int width,int height,
            @Coerce Object format,@Coerce Object directives,CallbackInfoReturnable<Boolean> ci) {
        // Let Iris perform its own complete attachment update when switching
        // between the screen and mirror, even if both counters equal one.
        if(currentDepthTexture!=texture)cachedDepthBufferVersion=version^Integer.MIN_VALUE;
        // Pinned Oculus rebinds ALL cached FBOs to replace their depth attachment.
        // With unchanged dimensions this updates topology only, not color data.
        // An exceptional exit aborts the outer history lease, which owns the scope.
        simplymirrors$historySetup=cachedWidth==width && cachedHeight==height;
        if(simplymirrors$historySetup)ShaderReflection.beginHistoryStorageSetup();
    }
    @Inject(method="resizeIfNeeded",at=@At("RETURN"))
    private void simplymirrors$depthSetupDone(int version,int texture,int width,int height,
            @Coerce Object format,@Coerce Object directives,CallbackInfoReturnable<Boolean> ci) {
        if(simplymirrors$historySetup)ShaderReflection.endHistoryStorageSetup();
        simplymirrors$historySetup=false;
    }
}
