package com.nstut.simplymirrors.client.mixin;
import com.nstut.simplymirrors.client.MirrorRenderer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=LevelRenderer.class,priority=900)
public abstract class LevelRendererMixin {
    @Inject(method="renderLevel",at=@At("TAIL"))
    private void simplymirrors$reflect(GraphicsResourceAllocator allocator,DeltaTracker delta,boolean outline,
            CameraRenderState camera,Matrix4fc view,GpuBufferSlice fog,Vector4f color,boolean sky,
            ChunkSectionsToRender sections,CallbackInfo ci) {
        MirrorRenderer.render(allocator,delta,camera,view,fog,color,sky);
    }
    @Inject(method="addCloudsPass",at=@At("HEAD"),cancellable=true)
    private void simplymirrors$skipReflectionClouds(CallbackInfo ci) { if(MirrorRenderer.redirect!=null)ci.cancel(); }
}
