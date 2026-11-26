package com.nstut.simplymirrors.client.mixin;

import com.nstut.simplymirrors.client.MirrorApertureCulling;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Restricts only the tagged reflection frustum; shadow cameras remain untouched. */
@Mixin(Frustum.class)
public abstract class MirrorFrustumMixin {
    @Inject(method = "isVisible", at = @At("HEAD"), cancellable = true)
    private void simplymirrors$mirrorAperture(AABB bounds, CallbackInfoReturnable<Boolean> cir) {
        if (!MirrorApertureCulling.mayBeVisible((Frustum)(Object)this, bounds))
            cir.setReturnValue(false);
    }
}
