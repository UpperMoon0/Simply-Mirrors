package com.nstut.simplymirrors.client.mixin;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.nstut.simplymirrors.client.MirrorRenderer;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method="getMainRenderTarget",at=@At("HEAD"),cancellable=true)
    private void simplymirrors$target(CallbackInfoReturnable<RenderTarget> ci) {
        if(MirrorRenderer.redirect!=null)ci.setReturnValue(MirrorRenderer.redirect);
    }
    @Inject(method="runTick",at=@At("HEAD"))
    private void simplymirrors$unload(CallbackInfo ci) { if(Minecraft.getInstance().level==null)MirrorRenderer.release(); }
    @Inject(method="close",at=@At("HEAD"),remap=false)
    private void simplymirrors$close(CallbackInfo ci) { MirrorRenderer.release(); }
}
