package com.nstut.simplymirrors.client.mixin;

import com.nstut.simplymirrors.client.MirrorApertureCulling;
import org.joml.FrustumIntersection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Optional Embeddium 0.3.31 / Sodium viewport culling integration.
 * The target is never required in a vanilla, Oculus-only, or unsupported
 * loader configuration. FrustumIntersection identity restricts this filter
 * to the tagged reflective view rather than Iris shadow views.
 */
@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.render.viewport.frustum.SimpleFrustum",
       remap = false)
public abstract class EmbeddiumFrustumMixin {
    @Shadow(remap = false) private FrustumIntersection frustum;

    @Inject(method = "testAab(FFFFFF)Z", at = @At("HEAD"),
            cancellable = true, require = 0, remap = false)
    private void simplymirrors$clipSodiumSection(float minX, float minY, float minZ,
                                                   float maxX, float maxY, float maxZ,
                                                   CallbackInfoReturnable<Boolean> cir) {
        if (!MirrorApertureCulling.mayBeVisibleEmbeddium(frustum,
                minX, minY, minZ, maxX, maxY, maxZ)) {
            cir.setReturnValue(false);
        }
    }
}
