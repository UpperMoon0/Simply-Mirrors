package com.nstut.simplymirrors.client.mixin;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {
    @Accessor("levelRenderState") LevelRenderState simplymirrors$state();
    @Accessor("viewArea") ViewArea simplymirrors$viewArea();
}
