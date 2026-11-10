package com.nstut.simplymirrors.client.mixin;

import com.nstut.simplymirrors.client.ReflectionScissor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Avoid reusing a stale shader-program name after a shader-pack reload. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.gl.program.Program", remap = false)
public abstract class IrisProgramDisposeMixin {
    @Shadow public abstract int getProgramId();

    @Inject(method = "destroyInternal", at = @At("HEAD"), require = 0)
    private void simplymirrors$forgetProgram(CallbackInfo info) {
        ReflectionScissor.unregisterShaderProgram(getProgramId());
    }
}
