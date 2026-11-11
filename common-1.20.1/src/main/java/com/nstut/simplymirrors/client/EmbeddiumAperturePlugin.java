package com.nstut.simplymirrors.client;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Main 1.20.1 mixin config companion. Ordinary mixins are always admitted;
 * Embeddium's soft target is opt-in and must never affect normal gameplay.
 */
public final class EmbeddiumAperturePlugin implements IMixinConfigPlugin {
    @Override public void onLoad(String mixinPackage) {
        if (Boolean.getBoolean("simplymirrors.apertureCull"))
            System.out.println("SIMPLY_MIRRORS_EMBEDDIUM_PLUGIN_LOADED");
    }
    @Override public String getRefMapperConfig() { return null; }

    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!mixinClassName.endsWith(".EmbeddiumFrustumMixin")) return true;
        boolean enabled = Boolean.getBoolean("simplymirrors.apertureCull");
        boolean targetMatches = targetClassName.equals(
                "me.jellysquid.mods.sodium.client.render.viewport.frustum.SimpleFrustum");
        if (enabled)
            System.out.println("SIMPLY_MIRRORS_EMBEDDIUM_PLUGIN_DECISION targetMatches="
                    + targetMatches + " apply=" + (enabled && targetMatches));
        return enabled && targetMatches;
    }

    @Override public void acceptTargets(Set<String> own, Set<String> other) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass,
                                   String mixinClassName, IMixinInfo mixinInfo) { }
    @Override public void postApply(String targetClassName, ClassNode targetClass,
                                    String mixinClassName, IMixinInfo mixinInfo) {
        if (Boolean.getBoolean("simplymirrors.apertureCull")
                && mixinClassName.endsWith(".EmbeddiumFrustumMixin"))
            System.out.println("SIMPLY_MIRRORS_EMBEDDIUM_PLUGIN_APPLIED target=" + targetClassName);
    }
}
