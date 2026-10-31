package com.nstut.simplymirrors.visualtest;
import com.nstut.simplymirrors.visualsupport.Atm10FramebufferTrace;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** TEMPORARY ATM10 QA ONLY: before and after world draws. */
@Mixin(value = LevelRenderer.class, priority = 800)
public abstract class Atm10FramebufferLevelMixin {
    @Inject(method = "renderLevel", at = @At("HEAD"), require = 0)
    private void simplymirrors$worldHead(CallbackInfo ci) {
        Atm10FramebufferTrace.check("world-head");
    }
    @Inject(method = "renderLevel", at = @At("TAIL"), require = 0)
    private void simplymirrors$worldTail(CallbackInfo ci) {
        Atm10FramebufferTrace.check("world-tail");
    }
}
