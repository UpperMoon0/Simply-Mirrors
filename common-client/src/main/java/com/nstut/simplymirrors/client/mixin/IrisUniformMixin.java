package com.nstut.simplymirrors.client.mixin;
import com.nstut.simplymirrors.client.ShaderReflection;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Upload every draw: Iris's once-per-frame cache cannot observe a reflected camera. */
@Pseudo
@Mixin(targets={"net.irisshaders.iris.gl.program.ProgramUniforms","net.coderbot.iris.gl.program.ProgramUniforms"},remap=false)
public abstract class IrisUniformMixin {
    @Inject(method="update",at=@At("HEAD"),require=0)
    private void simplymirrors$mainCamera(CallbackInfo ci){ShaderReflection.beforeUniforms();}
    @Inject(method="update",at=@At("RETURN"),require=0)
    private void simplymirrors$plane(CallbackInfo ci){ShaderReflection.afterUniforms();}
}
