package com.nstut.simplymirrors.client.mixin;
import com.nstut.simplymirrors.client.MirrorRenderer;
import com.nstut.simplymirrors.client.MirrorApertureCulling;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Runs before terrain replacement hooks can cancel vanilla setupRender. */
@Mixin(value=LevelRenderer.class,priority=1100)
public abstract class ReflectionCullingMixin {
    // Embeddium creates its terrain viewport from the Frustum argument of
    // setupRender, which need not be the LevelRenderer.cullingFrustum field.
    // Tag the exact instance it receives, never the separate prepared field.
    @Inject(method="setupRender",at=@At("HEAD"))
    private void simplymirrors$reflectionCulling(Camera camera, Frustum frustum,
                                                  boolean updateChunks, boolean isSpectator,
                                                  CallbackInfo ci) {
        if (MirrorRenderer.redirect != null)
            MirrorApertureCulling.track(frustum, camera.getPosition());
        // Iris/Oculus reset this at renderLevel HEAD. Oblique clipping replaces
        // wall occlusion for the virtual camera behind the mirror.
        if(MirrorRenderer.redirect!=null)Minecraft.getInstance().smartCull=false;
    }
}
