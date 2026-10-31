package com.nstut.simplymirrors.visualtest;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.nstut.simplymirrors.visualsupport.Atm10FramebufferTrace;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** TEMPORARY ATM10 QA ONLY: check bound framebuffer after Minecraft changes targets. */
@Mixin(value = RenderTarget.class, priority = 800)
public abstract class Atm10FramebufferTargetMixin {
    @Inject(method = "bindWrite", at = @At("RETURN"), require = 0)
    private void simplymirrors$bound(CallbackInfo ci) {
        Atm10FramebufferTrace.check("target-bind-write");
    }
}
