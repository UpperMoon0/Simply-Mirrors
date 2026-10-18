package com.nstut.simplymirrors.client.mixin;
import com.nstut.simplymirrors.client.MirrorRenderer;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** Sodium obtains projection from GameRenderer instead of the reflected renderLevel argument. */
@Mixin(value=GameRenderer.class,priority=900)
public abstract class SodiumProjectionMixin {
    @Inject(method="sodium$getProjectionMatrix",at=@At("HEAD"),cancellable=true,remap=false,require=0)
    private void simplymirrors$projection(CallbackInfoReturnable<Matrix4fc> ci){if(MirrorRenderer.reflectionProjection!=null)ci.setReturnValue(MirrorRenderer.reflectionProjection);}
}
