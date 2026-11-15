package com.nstut.simplymirrors.client.mixin;
import com.nstut.simplymirrors.client.ShaderReflection;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Pseudo
@Mixin(targets="net.irisshaders.iris.gl.framebuffer.GlFramebuffer",remap=false)
public abstract class IrisHistoryFramebufferMixin {
    @Shadow public abstract int getId();
    @Inject(method={"bind","bindAsReadBuffer","bindAsDrawBuffer"},at=@At("RETURN"),require=0)
    private void simplymirrors$bound(CallbackInfo ci){ShaderReflection.historyFramebufferBound(getId());}
    @Inject(method="addColorAttachment",at=@At("RETURN"),require=0)
    private void simplymirrors$attached(int index,int texture,CallbackInfo ci){ShaderReflection.historyFramebufferBound(getId());}
}
