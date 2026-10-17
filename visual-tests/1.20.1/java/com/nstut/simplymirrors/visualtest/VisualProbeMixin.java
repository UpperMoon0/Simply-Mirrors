package com.nstut.simplymirrors.visualtest;
import com.nstut.simplymirrors.client.MirrorRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.core.registries.BuiltInRegistries;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.nio.file.*;
import static org.lwjgl.opengl.GL33.*;
@Mixin(Minecraft.class)
public abstract class VisualProbeMixin {
    @Unique private int simplymirrors$frames;
    @Unique private boolean simplymirrors$done;
    @Inject(method="runTick",at=@At("TAIL"))
    private void simplymirrors$probe(CallbackInfo ci) throws Exception {
        if(simplymirrors$done)return;Minecraft mc=Minecraft.getInstance();
        if(simplymirrors$frames++==0) {GLFW.glfwHideWindow(mc.getWindow().getWindow());mc.options.pauseOnLostFocus=false;mc.options.renderDistance().set(4);mc.options.bobView().set(false);mc.options.framerateLimit().set(30);mc.options.hideGui=true;}
        if(mc.player==null||mc.level==null)return;
        mc.player.setPos(.5,65,-3.5);mc.player.xo=.5;mc.player.yo=65;mc.player.zo=-3.5;
        mc.player.setYRot(0);mc.player.setXRot(0);mc.player.yRotO=0;mc.player.xRotO=0;mc.player.yBodyRot=0;mc.player.yHeadRot=0;
        if(MirrorRenderer.completedPasses<30&&simplymirrors$frames<1800)return;
        boolean tab=false;for(CreativeModeTab t:BuiltInRegistries.CREATIVE_MODE_TAB)if(t!=null&&t.getDisplayName().getString().equals("Simply Mirrors"))tab=true;
        int error=glGetError();Path dir=mc.gameDirectory.toPath().resolve("visual-evidence");Files.createDirectories(dir);
        VisualCapture.save(mc.getMainRenderTarget().getColorTextureId(),mc.getMainRenderTarget().width,mc.getMainRenderTarget().height,dir.resolve("mirror.png"));
        String json="{\"passes\":"+MirrorRenderer.completedPasses+",\"glError\":"+error+",\"creativeTab\":"+tab+"}";
        Files.write(dir.resolve("result.json"),json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        System.out.println("SIMPLY_MIRRORS_VISUAL_RESULT "+json);simplymirrors$done=true;mc.stop();
    }
}
