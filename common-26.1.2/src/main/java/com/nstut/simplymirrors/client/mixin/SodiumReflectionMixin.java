package com.nstut.simplymirrors.client.mixin;
import com.nstut.simplymirrors.client.MirrorRenderer;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
/** A second camera cannot reuse Sodium's asynchronous visibility result from the main camera. */
@Pseudo
@Mixin(targets="net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager",remap=false)
public abstract class SodiumReflectionMixin {
    @ModifyVariable(method="finalizeRenderLists",at=@At("HEAD"),argsOnly=true,ordinal=0)
    private boolean simplymirrors$immediateVisibility(boolean immediate){return immediate || MirrorRenderer.redirect!=null;}
}
