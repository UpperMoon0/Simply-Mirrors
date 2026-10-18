package com.nstut.simplymirrors.client.mixin;
import com.nstut.simplymirrors.math.ShaderClippingTransformation;
import java.util.Map;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Pseudo
@Mixin(targets="net.coderbot.iris.pipeline.transform.TransformPatcher",remap=false)
public abstract class IrisLegacyTransformMixin {
    @Inject(method="patchSodiumTerrain",at=@At("RETURN"),cancellable=true)
    private static void simplymirrors$terrain(CallbackInfoReturnable<Map> ci){ci.setReturnValue(ShaderClippingTransformation.transformProgram("SODIUM","gbuffers_terrain",ci.getReturnValue()));}
    @Inject(method="patchAttributes",at=@At("RETURN"),cancellable=true)
    private static void simplymirrors$world(CallbackInfoReturnable<Map> ci){ci.setReturnValue(ShaderClippingTransformation.transformProgram("VANILLA","entities_solid",ci.getReturnValue()));}
}
