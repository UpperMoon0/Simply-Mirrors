package com.nstut.simplymirrors.client.mixin;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(Camera.class)
public interface CameraAccessor {
    @Invoker("setPosition") void simplymirrors$position(Vec3 position);
    @Invoker("setRotation") void simplymirrors$rotation(float yaw, float pitch);
}
