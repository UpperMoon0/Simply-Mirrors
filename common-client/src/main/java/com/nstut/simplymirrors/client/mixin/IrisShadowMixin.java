package com.nstut.simplymirrors.client.mixin;
import com.nstut.simplymirrors.client.MirrorRenderer;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import static org.lwjgl.opengl.GL33.*;
/** Shadow maps use the light's camera and ordinary winding, without the mirror clip plane. */
@Pseudo
@Mixin(targets={"net.irisshaders.iris.pipeline.IrisRenderingPipeline","net.coderbot.iris.pipeline.newshader.NewWorldRenderingPipeline","net.coderbot.iris.pipeline.DeferredWorldRenderingPipeline"},remap=false)
public abstract class IrisShadowMixin {
    @Inject(method="beginLevelRendering",at=@At("RETURN"),require=0)
    private void simplymirrors$terrainVisibility(CallbackInfo ci){
        if(MirrorRenderer.redirect!=null)net.minecraft.client.Minecraft.getInstance().smartCull=false;
    }
    @Unique private boolean simplymirrors$clip;
    @Unique private int simplymirrors$front;
    @Inject(method="renderShadows",at=@At("HEAD"),require=0)
    private void simplymirrors$shadow(CallbackInfo ci){if(MirrorRenderer.redirect!=null){simplymirrors$clip=glIsEnabled(GL_CLIP_DISTANCE0);simplymirrors$front=glGetInteger(GL_FRONT_FACE);glDisable(GL_CLIP_DISTANCE0);glFrontFace(GL_CCW);}}
    @Inject(method="renderShadows",at=@At("RETURN"),require=0)
    private void simplymirrors$restore(CallbackInfo ci){if(MirrorRenderer.redirect!=null){if(simplymirrors$clip)glEnable(GL_CLIP_DISTANCE0);glFrontFace(simplymirrors$front);}}
}
