package com.nstut.simplymirrors.client.mixin;

import com.google.common.collect.ImmutableList;
import com.nstut.simplymirrors.client.ShaderReflection;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Oculus 1.8 copies next-frame history AFTER the final draw. A redirected
 * reflection immediately discards that private storage, so those copies have
 * no consumer. Keep final shaders, mip/filter resets and all binding cleanup.
 */
@Pseudo
@Mixin(targets="net.irisshaders.iris.pipeline.FinalPassRenderer", remap=false)
public abstract class IrisDiscardHistoryMixin {
    @Shadow @Final private ImmutableList<?> swapPasses;
    @Unique private boolean simplymirrors$reported;

    @Redirect(method="renderFinalPass", at=@At(value="FIELD",
        target="Lnet/irisshaders/iris/pipeline/FinalPassRenderer;swapPasses:Lcom/google/common/collect/ImmutableList;"), require=0)
    private ImmutableList<?> simplymirrors$discardPrivateHistory(@Coerce Object renderer) {
        if (!ShaderReflection.discardFinalHistory()) return swapPasses;
        if (!simplymirrors$reported && !swapPasses.isEmpty()) {
            System.out.println("SIMPLY_MIRRORS_DISCARD_FINAL_HISTORY copies="+swapPasses.size());
            simplymirrors$reported=true;
        }
        return ImmutableList.of();
    }
}
