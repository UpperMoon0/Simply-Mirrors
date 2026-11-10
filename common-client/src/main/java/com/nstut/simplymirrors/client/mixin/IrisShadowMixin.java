package com.nstut.simplymirrors.client.mixin;
import com.nstut.simplymirrors.client.MirrorRenderer;
import com.nstut.simplymirrors.client.MirrorGpuProfiler;
import com.nstut.simplymirrors.client.ShaderReflection;
import com.nstut.simplymirrors.client.ReflectionScissor;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import static org.lwjgl.opengl.GL33.*;
/** Shadow maps use the light's camera and ordinary winding, without the mirror clip plane. */
@Pseudo
@Mixin(targets={"net.irisshaders.iris.pipeline.IrisRenderingPipeline","net.coderbot.iris.pipeline.newshader.NewWorldRenderingPipeline","net.coderbot.iris.pipeline.DeferredWorldRenderingPipeline"},remap=false)
public abstract class IrisShadowMixin {
    @Unique private boolean simplymirrors$beginScissor;
    @Unique private boolean simplymirrors$shadowScissor;
    @Inject(method="beginLevelRendering",at=@At("HEAD"),require=0)
    private void simplymirrors$beginFullShaderClear(CallbackInfo ci){
        if(MirrorRenderer.redirect!=null){
            // Iris clears its full-resolution shadow depth and executes begin
            // programs in this phase. Do not clip those shadow/global passes.
            simplymirrors$beginScissor=glIsEnabled(GL_SCISSOR_TEST);
            glDisable(GL_SCISSOR_TEST);
        }
    }
    @Inject(method="beginLevelRendering",at=@At("RETURN"),require=0)
    private void simplymirrors$terrainVisibility(CallbackInfo ci){
        if(MirrorRenderer.redirect!=null){
            net.minecraft.client.Minecraft.getInstance().smartCull=false;
            if(simplymirrors$beginScissor)glEnable(GL_SCISSOR_TEST);
            else glDisable(GL_SCISSOR_TEST);
        }
    }
    // Whole deferred/final stages may clear or draw to size.buffer.* targets
    // before the fullscreen-quad callback. Do not retain a main-viewport
    // scissor across their framebuffer changes, including target clears.
    @Unique private boolean simplymirrors$deferredScissor;
    @Unique private boolean simplymirrors$finalScissor;
    @Inject(method="beginTranslucents",at=@At("HEAD"),require=0)
    private void simplymirrors$deferredFullTarget(CallbackInfo ci) {
        if(MirrorRenderer.redirect!=null) {
            MirrorGpuProfiler.translucentBegin();
            simplymirrors$deferredScissor=glIsEnabled(GL_SCISSOR_TEST);
            glDisable(GL_SCISSOR_TEST);
        }
    }
    @Inject(method="beginTranslucents",at=@At("RETURN"),require=0)
    private void simplymirrors$deferredDone(CallbackInfo ci) {
        if(MirrorRenderer.redirect!=null) {
            MirrorGpuProfiler.translucentEnd();
            if(simplymirrors$deferredScissor)glEnable(GL_SCISSOR_TEST);
            else glDisable(GL_SCISSOR_TEST);
        }
    }
    @Inject(method="finalizeLevelRendering",at=@At("HEAD"),require=0)
    private void simplymirrors$finalFullTarget(CallbackInfo ci) {
        if(MirrorRenderer.redirect!=null) {
            MirrorGpuProfiler.finalizeBegin();
            ReflectionScissor.setFinalStage(true);
            simplymirrors$finalScissor=glIsEnabled(GL_SCISSOR_TEST);
            glDisable(GL_SCISSOR_TEST);
        }
    }
    @Inject(method="finalizeLevelRendering",at=@At("RETURN"),require=0)
    private void simplymirrors$finalDone(CallbackInfo ci) {
        if(MirrorRenderer.redirect!=null) {
            MirrorGpuProfiler.finalizeEnd();
            ReflectionScissor.setFinalStage(false);
            if(simplymirrors$finalScissor)glEnable(GL_SCISSOR_TEST);
            else glDisable(GL_SCISSOR_TEST);
        }
    }
    @Unique private boolean simplymirrors$clip;
    @Unique private int simplymirrors$front;
    @Inject(method="renderShadows",at=@At("HEAD"),require=0)
    private void simplymirrors$shadow(CallbackInfo ci){if(MirrorRenderer.redirect!=null){
        simplymirrors$shadowScissor=glIsEnabled(GL_SCISSOR_TEST);
        glDisable(GL_SCISSOR_TEST);
        simplymirrors$clip=ShaderReflection.suspend();
        simplymirrors$front=glGetInteger(GL_FRONT_FACE);
        glFrontFace(GL_CCW);
        MirrorGpuProfiler.shadowBegin();
    }}
    @Inject(method="renderShadows",at=@At("RETURN"),require=0)
    private void simplymirrors$restore(CallbackInfo ci){if(MirrorRenderer.redirect!=null){
        MirrorGpuProfiler.shadowEnd();
        ShaderReflection.resume(simplymirrors$clip);
        glFrontFace(simplymirrors$front);
        if(simplymirrors$shadowScissor)glEnable(GL_SCISSOR_TEST);
        else glDisable(GL_SCISSOR_TEST);
    }}
}
