package com.nstut.simplymirrors.client.mixin;
import com.nstut.simplymirrors.client.MirrorRenderer;
import com.nstut.simplymirrors.client.ShaderReflection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import static org.lwjgl.opengl.GL33.*;
/** Shader screen-space passes retain ordinary winding in a reflected world. */
@Pseudo
@Mixin(targets={"net.irisshaders.iris.pathways.FullScreenQuadRenderer","net.coderbot.iris.pathways.FullScreenQuadRenderer","net.coderbot.iris.postprocess.FullScreenQuadRenderer",
    "net.irisshaders.iris.pipeline.CompositeRenderer","net.irisshaders.iris.pipeline.FinalPassRenderer",
    "net.irisshaders.iris.pathways.HorizonRenderer"},remap=false)
public abstract class IrisFullscreenMixin {
    @Unique private int simplymirrors$frontFace;
    @Unique private boolean simplymirrors$clip;
    @Inject(method={"renderQuad","renderAll","renderFinalPass","renderHorizon"},at=@At("HEAD"),require=0)
    private void simplymirrors$screenWinding(CallbackInfo ci) {
        if(MirrorRenderer.redirect!=null){simplymirrors$frontFace=glGetInteger(GL_FRONT_FACE);glFrontFace(GL_CCW);simplymirrors$clip=ShaderReflection.suspend();}
    }
    @Inject(method={"renderQuad","renderAll","renderFinalPass","renderHorizon"},at=@At("RETURN"),require=0)
    private void simplymirrors$restoreWinding(CallbackInfo ci) {
        if(MirrorRenderer.redirect!=null){glFrontFace(simplymirrors$frontFace);ShaderReflection.resume(simplymirrors$clip);}
    }
}
