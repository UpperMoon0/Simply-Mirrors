package com.nstut.simplymirrors.client.mixin;
import com.nstut.simplymirrors.math.ShaderClippingTransformation;
import java.util.Map;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** Copy transformed world sources rather than mutating Iris's source-only cache. */
@Pseudo
@Mixin(targets={"net.irisshaders.iris.pipeline.transform.TransformPatcher","net.coderbot.iris.pipeline.transform.TransformPatcher"},remap=false)
public abstract class IrisTransformMixin {
    @Unique private static String simplymirrors$name;
    @ModifyVariable(method="transform",at=@At("HEAD"),argsOnly=true,ordinal=0,require=0)
    private static String simplymirrors$name(String name){simplymirrors$name=name;return name;}
    @Inject(method="transform",at=@At("RETURN"),cancellable=true,require=0)
    private static void simplymirrors$clip(CallbackInfoReturnable<Map> ci){
        String name=simplymirrors$name;
        if(name!=null)ci.setReturnValue(ShaderClippingTransformation.transformProgram(name.startsWith("gbuffers_")?"SODIUM":"VANILLA",name,ci.getReturnValue()));
    }
}
