package com.nstut.simplymirrors.client.mixin;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Never apply the optional renderer hook in normal gameplay. */
public final class EmbeddiumAperturePlugin implements IMixinConfigPlugin {
    @Override public void onLoad(String mixinPackage) { }
    @Override public String getRefMapperConfig() { return null; }

    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return Boolean.getBoolean("simplymirrors.apertureCull")
                && mixinClassName.endsWith(".EmbeddiumFrustumMixin")
                && targetClassName.equals(
                    "me.jellysquid.mods.sodium.client.render.viewport.frustum.SimpleFrustum");
    }

    @Override public void acceptTargets(Set<String> own, Set<String> other) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass,
                                   String mixinClassName, IMixinInfo mixinInfo) { }
    @Override public void postApply(String targetClassName, ClassNode targetClass,
                                    String mixinClassName, IMixinInfo mixinInfo) { }
}
