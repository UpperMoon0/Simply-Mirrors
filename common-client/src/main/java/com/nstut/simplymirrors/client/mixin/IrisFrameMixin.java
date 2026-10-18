package com.nstut.simplymirrors.client.mixin;
import com.nstut.simplymirrors.client.ShaderReflection;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** A second camera is not another frame: preserve camera history and smoothing state. */
@Pseudo
@Mixin(targets={"net.irisshaders.iris.uniforms.FrameUpdateNotifier","net.coderbot.iris.uniforms.FrameUpdateNotifier"},remap=false)
public abstract class IrisFrameMixin {
    @Inject(method="onNewFrame",at=@At("HEAD"),cancellable=true,require=0)
    private void simplymirrors$frame(CallbackInfo ci){if(ShaderReflection.reflecting())ci.cancel();}
}
