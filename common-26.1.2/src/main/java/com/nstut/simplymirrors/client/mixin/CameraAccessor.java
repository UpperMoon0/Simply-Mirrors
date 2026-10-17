package com.nstut.simplymirrors.client.mixin;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(Camera.class)
public interface CameraAccessor {
    @Invoker("setPosition") void simplymirrors$position(Vec3 position);
    @Invoker("setRotation") void simplymirrors$rotation(float yaw,float pitch);
    @Accessor("detached") void simplymirrors$detached(boolean detached);
    @Invoker("prepareCullFrustum") void simplymirrors$frustum(Matrix4fc view,Matrix4fc projection,Vec3 position);
}
