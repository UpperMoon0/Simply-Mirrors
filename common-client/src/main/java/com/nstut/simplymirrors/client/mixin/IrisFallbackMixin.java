package com.nstut.simplymirrors.client.mixin;
import com.nstut.simplymirrors.client.ShaderReflection;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** FallbackShader binds programs directly and never calls ProgramUniforms.update. */
@Pseudo
@Mixin(targets="net.irisshaders.iris.pipeline.programs.FallbackShader",remap=false)
public abstract class IrisFallbackMixin {
    @Inject(method={"apply","iris$setupState"},at=@At("RETURN"),require=0)
    private void simplymirrors$unpatchedProgram(CallbackInfo ci){ShaderReflection.upload();}
}
