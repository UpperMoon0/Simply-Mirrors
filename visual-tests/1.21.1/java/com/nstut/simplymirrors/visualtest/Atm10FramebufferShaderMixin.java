package com.nstut.simplymirrors.visualtest;
import com.nstut.simplymirrors.client.ShaderReflection;
import com.nstut.simplymirrors.visualsupport.Atm10FramebufferTrace;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** TEMPORARY ATM10 QA ONLY: isolate shader-history transitions. */
@Mixin(value = ShaderReflection.class, remap = false)
public abstract class Atm10FramebufferShaderMixin {
    @Inject(method = "begin", at = @At("HEAD"), require = 0)
    private static void simplymirrors$beginHead(CallbackInfo ci) {
        Atm10FramebufferTrace.check("shader-begin-head");
    }
    @Inject(method = "begin", at = @At("RETURN"), require = 0)
    private static void simplymirrors$beginReturn(CallbackInfo ci) {
        Atm10FramebufferTrace.check("shader-begin-return");
    }
    @Inject(method = "end", at = @At("HEAD"), require = 0)
    private static void simplymirrors$endHead(CallbackInfo ci) {
        Atm10FramebufferTrace.check("shader-end-head");
    }
    @Inject(method = "end", at = @At("RETURN"), require = 0)
    private static void simplymirrors$endReturn(CallbackInfo ci) {
        Atm10FramebufferTrace.check("shader-end-return");
    }
}
