package com.nstut.simplymirrors.client.mixin;

import com.nstut.simplymirrors.client.MirrorApertureCulling;
import org.joml.FrustumIntersection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
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
    @Unique private static boolean simplymirrors$hookLogged;

    @Inject(method = "testAab(FFFFFF)Z", at = @At("HEAD"),
            cancellable = true, require = 0, remap = false)
    private void simplymirrors$clipSodiumSection(float minX, float minY, float minZ,
                                                   float maxX, float maxY, float maxZ,
                                                   CallbackInfoReturnable<Boolean> cir) {
        // One-time proof that Embeddium calls the transformed target.
        if (!simplymirrors$hookLogged && Boolean.getBoolean("simplymirrors.apertureCull")) {
            simplymirrors$hookLogged = true;
            System.out.println("SIMPLY_MIRRORS_EMBEDDIUM_FRUSTUM_HOOK_FIRST_CALL");
        }
        if (!MirrorApertureCulling.mayBeVisibleEmbeddium(frustum,
                minX, minY, minZ, maxX, maxY, maxZ)) {
            cir.setReturnValue(false);
        }
    }
}
