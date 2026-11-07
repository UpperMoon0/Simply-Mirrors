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
    @Unique private boolean simplymirrors$scissor;
    @Inject(method={"renderQuad","renderAll","renderFinalPass","renderHorizon"},at=@At("HEAD"),require=0)
    private void simplymirrors$screenWinding(CallbackInfo ci) {
        if(MirrorRenderer.redirect!=null){
            // Iris can bind half/quarter-resolution size.buffer.* targets during
            // deferred/composite/fullscreen draws. A scissor computed for the
            // main 1:1 framebuffer is invalid there: never clip those passes.
            simplymirrors$scissor=glIsEnabled(GL_SCISSOR_TEST);
            glDisable(GL_SCISSOR_TEST);
            simplymirrors$frontFace=glGetInteger(GL_FRONT_FACE);
            glFrontFace(GL_CCW);
            simplymirrors$clip=ShaderReflection.suspend();
        }
    }
    @Inject(method={"renderQuad","renderAll","renderFinalPass","renderHorizon"},at=@At("RETURN"),require=0)
    private void simplymirrors$restoreWinding(CallbackInfo ci) {
        if(MirrorRenderer.redirect!=null){
            glFrontFace(simplymirrors$frontFace);
            ShaderReflection.resume(simplymirrors$clip);
            if(simplymirrors$scissor)glEnable(GL_SCISSOR_TEST);
            else glDisable(GL_SCISSOR_TEST);
        }
    }
}
