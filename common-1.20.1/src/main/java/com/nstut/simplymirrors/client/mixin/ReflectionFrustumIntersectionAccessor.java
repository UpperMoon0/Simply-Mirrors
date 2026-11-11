package com.nstut.simplymirrors.client.mixin;

import net.minecraft.client.renderer.culling.Frustum;
import org.joml.FrustumIntersection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The exact vanilla intersection that Embeddium reuses for its viewport. */
@Mixin(Frustum.class)
public interface ReflectionFrustumIntersectionAccessor {
    @Accessor("intersection")
    FrustumIntersection simplymirrors$getIntersection();
}
