package com.nstut.simplymirrors.client.mixin;
import com.nstut.simplymirrors.client.ShaderReflection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
@Pseudo
@Mixin(targets="net.irisshaders.iris.gl.IrisRenderSystem",remap=false)
public abstract class IrisHistoryTextureMixin {
    @ModifyVariable(method={"generateMipmaps","texParameteri","texParameterf","texParameteriv","copyTexSubImage2D","getTexParameteri"},
        at=@At("HEAD"),argsOnly=true,ordinal=0,require=0)
    private static int simplymirrors$texture(int texture){return ShaderReflection.historyTexture(texture);}
    @ModifyVariable(method="bindTextureToUnit",at=@At("HEAD"),argsOnly=true,ordinal=2,require=0)
    private static int simplymirrors$unit(int texture,int target,int unit,int ignored){return ShaderReflection.historyTextureBinding(target,unit,texture);}
    @ModifyVariable(method="bindTextureForSetup",at=@At("HEAD"),argsOnly=true,ordinal=1,require=0)
    private static int simplymirrors$setup(int texture,int target,int ignored){return ShaderReflection.historyCurrentTextureBinding(texture);}
    @ModifyVariable(method="bindImageTexture",at=@At("HEAD"),argsOnly=true,ordinal=1,require=0)
    private static int simplymirrors$image(int texture,int unit,int ignored,int level,boolean layered,int layer,int access,int format){return ShaderReflection.historyImageBinding(unit,texture);}
}
