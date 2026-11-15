package com.nstut.simplymirrors.client.mixin;
import com.mojang.blaze3d.platform.GlStateManager;
import com.nstut.simplymirrors.client.ShaderReflection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
/** Covers cached FinalPassRenderer swap destination binds as well as legacy DSA fallback. */
@Mixin(value=GlStateManager.class,remap=false)
public abstract class HistoryTextureBindingMixin {
    @ModifyVariable(method="_bindTexture",at=@At("HEAD"),argsOnly=true,ordinal=0)
    private static int simplymirrors$history(int texture){return ShaderReflection.historyCurrentTextureBinding(texture);}
}
