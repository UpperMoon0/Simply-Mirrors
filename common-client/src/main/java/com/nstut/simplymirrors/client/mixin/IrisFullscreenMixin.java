package com.nstut.simplymirrors.client.mixin;
import com.nstut.simplymirrors.client.MirrorRenderer;
import com.nstut.simplymirrors.client.ShaderReflection;
import com.nstut.simplymirrors.client.ReflectionScissor;
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
    @Unique private boolean simplymirrors$overrodeScissorBox;
    @Unique private final int[] simplymirrors$oldScissorBox=new int[4];
    @Inject(method={"renderQuad","renderAll","renderFinalPass","renderHorizon"},at=@At("HEAD"),require=0)
    private void simplymirrors$screenWinding(CallbackInfo ci) {
        if(MirrorRenderer.redirect!=null){
            // Iris can bind half/quarter-resolution size.buffer.* targets during
            // deferred/composite/fullscreen draws. A scissor computed for the
            // main 1:1 framebuffer is invalid there: never clip those passes.
            simplymirrors$scissor=glIsEnabled(GL_SCISSOR_TEST);
            simplymirrors$overrodeScissorBox=false;
            // Only explicit renderQuad calls can be clipped. renderAll/final
            // can clear or mutate history attachments across target changes.
            // The experiment defaults OFF until shader visual acceptance passes.
            int[] scaled="renderQuad".equals(ci.getId()) &&
                    ShaderReflection.canClipShaderPass()
                    ?ReflectionScissor.currentShaderViewportBounds():null;
            if(scaled!=null){
                int[] previous=new int[4];
                glGetIntegerv(GL_SCISSOR_BOX,previous);
                System.arraycopy(previous,0,simplymirrors$oldScissorBox,0,4);
                glScissor(scaled[0],scaled[1],scaled[2],scaled[3]);
                glEnable(GL_SCISSOR_TEST);
                simplymirrors$overrodeScissorBox=true;
            }else glDisable(GL_SCISSOR_TEST);
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
            if(simplymirrors$overrodeScissorBox)
                glScissor(simplymirrors$oldScissorBox[0],simplymirrors$oldScissorBox[1],
                          simplymirrors$oldScissorBox[2],simplymirrors$oldScissorBox[3]);
            if(simplymirrors$scissor)glEnable(GL_SCISSOR_TEST);
            else glDisable(GL_SCISSOR_TEST);
        }
    }
}
