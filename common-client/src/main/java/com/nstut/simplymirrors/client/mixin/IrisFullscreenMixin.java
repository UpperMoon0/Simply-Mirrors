package com.nstut.simplymirrors.client.mixin;
import com.nstut.simplymirrors.client.MirrorRenderer;
import com.nstut.simplymirrors.client.ShaderReflection;
import com.nstut.simplymirrors.client.ReflectionScissor;
import org.lwjgl.BufferUtils;
import java.nio.IntBuffer;
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
    @Unique private boolean simplymirrors$outputClipped;
    @Unique private final int[] simplymirrors$scissorBox = new int[4];
    @Inject(method="renderQuad",at=@At("HEAD"),require=0)
    private void simplymirrors$screenQuad(CallbackInfo ci) { simplymirrors$screenWinding(true); }
    @Inject(method={"renderAll","renderFinalPass","renderHorizon"},at=@At("HEAD"),require=0)
    private void simplymirrors$screenOther(CallbackInfo ci) { simplymirrors$screenWinding(false); }
    @Unique private void simplymirrors$screenWinding(boolean outputEligible) {
        if(MirrorRenderer.redirect!=null){
            // Iris can bind half/quarter-resolution size.buffer.* targets during
            // deferred/composite/fullscreen draws. A scissor computed for the
            // main 1:1 framebuffer is invalid there: never clip those passes.
            simplymirrors$scissor=glIsEnabled(GL_SCISSOR_TEST);
            simplymirrors$outputClipped=false;
            glDisable(GL_SCISSOR_TEST);
            simplymirrors$frontFace=glGetInteger(GL_FRONT_FACE);
            glFrontFace(GL_CCW);
            simplymirrors$clip=ShaderReflection.suspend();
            if(outputEligible && (ReflectionScissor.outputScissorEnabled() ||
                                  ReflectionScissor.postProcessCropPossible())) {
                IntBuffer box=BufferUtils.createIntBuffer(4);
                glGetIntegerv(GL_SCISSOR_BOX,box);
                for(int i=0;i<4;i++) simplymirrors$scissorBox[i]=box.get(i);
                simplymirrors$outputClipped=ReflectionScissor.constrainFinalOutputQuad() ||
                    ReflectionScissor.constrainPostProcessQuad();
            }
        }
    }
    @Inject(method={"renderQuad","renderAll","renderFinalPass","renderHorizon"},at=@At("RETURN"),require=0)
    private void simplymirrors$restoreWinding(CallbackInfo ci) {
        if(MirrorRenderer.redirect!=null){
            glFrontFace(simplymirrors$frontFace);
            ShaderReflection.resume(simplymirrors$clip);
            if(simplymirrors$outputClipped) glScissor(simplymirrors$scissorBox[0],
                simplymirrors$scissorBox[1],simplymirrors$scissorBox[2],
                simplymirrors$scissorBox[3]);
            if(simplymirrors$scissor)glEnable(GL_SCISSOR_TEST);
            else glDisable(GL_SCISSOR_TEST);
        }
    }
}
