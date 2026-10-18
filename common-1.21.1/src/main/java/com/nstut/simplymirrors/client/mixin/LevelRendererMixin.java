package com.nstut.simplymirrors.client.mixin;
import com.nstut.simplymirrors.client.MirrorRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.*;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=LevelRenderer.class,priority=900)
public abstract class LevelRendererMixin {
    @Inject(method="renderLevel",at=@At("TAIL"))
    private void simplymirrors$reflect(DeltaTracker delta, boolean outline, Camera camera, GameRenderer game, LightTexture light, Matrix4f view, Matrix4f projection, CallbackInfo ci) { MirrorRenderer.render(delta, camera, game, light, view, projection); }
}
