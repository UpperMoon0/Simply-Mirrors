package com.nstut.simplymirrors.client.mixin;

import com.google.common.collect.ImmutableSet;
import com.nstut.simplymirrors.client.ReflectionScissor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Oculus 1.8 program-name capture for the experimental pass-aware GPU crop.
 * Only applied to the Forge 1.20.1 test target. No shader dependency at link time.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.gl.program.ProgramBuilder", remap = false)
public abstract class IrisProgramLabelMixin {
    @Shadow @Final private int program;
    @Unique private String simplymirrors$shaderName;

    @Inject(method = "begin", at = @At("RETURN"), require = 0)
    private static void simplymirrors$labelBuilder(String name, String vertex,
            String geometry, String fragment, ImmutableSet<Integer> reserved,
            CallbackInfoReturnable<Object> callback) {
        Object result = callback.getReturnValue();
        if (result instanceof IrisProgramLabelMixin)
            ((IrisProgramLabelMixin) result).simplymirrors$shaderName = name;
    }

    @Inject(method = "build", at = @At("RETURN"), require = 0)
    private void simplymirrors$recordProgram(CallbackInfoReturnable<Object> callback) {
        if (callback.getReturnValue() != null)
            ReflectionScissor.registerShaderProgram(program, simplymirrors$shaderName);
    }
}
