package com.nstut.simplymirrors.visualtest;
import com.nstut.simplymirrors.visualsupport.Atm10FramebufferTrace;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** TEMPORARY ATM10 QA ONLY: Iris render-target/depth attachment transition. */
@Pseudo
@Mixin(targets = {"net.irisshaders.iris.targets.RenderTargets"}, remap = false, priority = 800)
public abstract class Atm10FramebufferIrisMixin {
    @Inject(method = "resizeIfNeeded", at = @At("HEAD"), require = 0)
    private void simplymirrors$before(CallbackInfoReturnable<Boolean> ci) {
        Atm10FramebufferTrace.check("iris-resize-head");
    }
    @Inject(method = "resizeIfNeeded", at = @At("RETURN"), require = 0)
    private void simplymirrors$after(CallbackInfoReturnable<Boolean> ci) {
        Atm10FramebufferTrace.check("iris-resize-return");
    }
}
