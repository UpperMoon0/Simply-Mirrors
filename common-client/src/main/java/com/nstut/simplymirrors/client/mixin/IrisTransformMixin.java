package com.nstut.simplymirrors.client.mixin;
import com.nstut.simplymirrors.math.ShaderClippingTransformation;
import com.nstut.simplymirrors.math.ShaderFinalFragmentGuard;
import com.nstut.simplymirrors.math.ShaderVolumetricWeightReuse;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.Map;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** Copy transformed world sources rather than mutating Iris's source-only cache. */
@Pseudo
@Mixin(targets={"net.irisshaders.iris.pipeline.transform.TransformPatcher","net.coderbot.iris.pipeline.transform.TransformPatcher"},remap=false)
public abstract class IrisTransformMixin {
    // Capture the original program argument directly at RETURN. There is no
    // HEAD/RETURN state coupling to leak when shader compilation throws.
    @Inject(method="transform",at=@At("RETURN"),cancellable=true,require=0)
    private static void simplymirrors$clip(CallbackInfoReturnable<Map> ci,
                                            @Local(argsOnly=true,ordinal=0) String name){
        if(name!=null) {
            Map transformed=ShaderClippingTransformation.transformProgram(
                    name.startsWith("gbuffers_")?"SODIUM":"VANILLA",name,ci.getReturnValue());
            if(Boolean.getBoolean("simplymirrors.shaderFinalFragmentGuard"))
                transformed=ShaderFinalFragmentGuard.transformProgram(name,transformed);
            if(Boolean.getBoolean("simplymirrors.shaderVolumetricWeightReuse")) {
                Map reused=ShaderVolumetricWeightReuse.transformProgram(name,transformed);
                if(reused!=transformed)
                    System.out.println("SIMPLY_MIRRORS_VOLUMETRIC_WEIGHT_REUSE program="+name);
                transformed=reused;
            }
            ci.setReturnValue(transformed);
        }
    }
}
