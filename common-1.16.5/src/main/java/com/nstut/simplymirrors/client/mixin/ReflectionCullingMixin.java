package com.nstut.simplymirrors.client.mixin;
import com.nstut.simplymirrors.client.MirrorRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Runs before terrain replacement hooks can cancel vanilla setupRender. */
@Mixin(value=LevelRenderer.class,priority=1100)
public abstract class ReflectionCullingMixin {
    @Inject(method="setupRender",at=@At("HEAD"))
    private void simplymirrors$reflectionCulling(CallbackInfo ci) {
        // Iris/Oculus reset this at renderLevel HEAD. Oblique clipping replaces
        // wall occlusion for the virtual camera behind the mirror.
        if(MirrorRenderer.redirect!=null)Minecraft.getInstance().smartCull=false;
    }
}
