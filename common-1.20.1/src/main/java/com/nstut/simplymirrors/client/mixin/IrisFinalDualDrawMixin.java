package com.nstut.simplymirrors.client.mixin;

import com.mojang.blaze3d.vertex.VertexBuffer;
import com.nstut.simplymirrors.client.MirrorRenderer;
import com.nstut.simplymirrors.client.ReflectionScissor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Opt-in second 1x1 draw of the exact final program's SSBO-clear fragment.
 *
 * Both pinned Oculus 1.8.0 and Iris 1.7.6 have a persistent quad buffer and
 * renderQuad -> VertexBuffer.draw -> restorePolygonMode. Hook BEFORE restore
 * so the already-bound shader, framebuffer and quad remain identical.
 *
 * Production playback does not enable this experiment.
 */
@Pseudo
@Mixin(targets="net.irisshaders.iris.pathways.FullScreenQuadRenderer", remap=false)
public abstract class IrisFinalDualDrawMixin {
    @Shadow @Final private VertexBuffer quad;

    @Inject(method="renderQuad",
            at=@At(value="INVOKE",
                target="Lnet/irisshaders/iris/gl/IrisRenderSystem;restorePolygonMode()V",
                shift=At.Shift.BEFORE,
                remap=false),
            require=0)
    private void simplymirrors$drawOriginFragment(CallbackInfo ci) {
        if (MirrorRenderer.redirect != null)
            ReflectionScissor.drawFinalOriginPixel(() -> quad.draw());
    }
}
