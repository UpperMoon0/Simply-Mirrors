package com.nstut.simplymirrors.visualtest;
import com.nstut.simplymirrors.client.MirrorRenderer;
import com.nstut.simplymirrors.visualsupport.VisualCapture;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
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
    @Unique private boolean simplymirrors$joining;
    @Inject(method="runTick",at=@At("TAIL"))
    private void simplymirrors$probe(CallbackInfo ci) throws Exception {
        if(simplymirrors$done)return;Minecraft mc=Minecraft.getInstance();
        if(simplymirrors$frames++==0) {mc.options.pauseOnLostFocus=false;mc.options.renderDistance().set(4);mc.options.bobView().set(false);mc.options.framerateLimit().set(30);mc.options.hideGui=true;}
        if(mc.player==null||mc.level==null) {
            if(simplymirrors$frames%300==0)System.out.println("SIMPLY_MIRRORS_VISUAL_STAGE "+(mc.screen==null?"none":mc.screen.getClass().getName()));
            if(!simplymirrors$joining&&mc.getOverlay()==null&&mc.screen instanceof TitleScreen) {
                simplymirrors$joining=true;String port=System.getProperty("simplymirrors.visualPort");String address="127.0.0.1:"+port;
                System.out.println("SIMPLY_MIRRORS_VISUAL_CONNECT "+address);ConnectScreen.startConnecting(new TitleScreen(),mc,ServerAddress.parseString(address),new ServerData("Mirror test",address,ServerData.Type.OTHER),false,null);
            }
            return;
        }
        mc.player.setPos(.5,65,-3.5);mc.player.xo=.5;mc.player.yo=65;mc.player.zo=-3.5;
        mc.player.setYRot(0);mc.player.setXRot(0);mc.player.yRotO=0;mc.player.xRotO=0;mc.player.yBodyRot=0;mc.player.yHeadRot=0;
        if(MirrorRenderer.completedPasses<30&&simplymirrors$frames<1800)return;
        boolean tab=false;for(CreativeModeTab t:BuiltInRegistries.CREATIVE_MODE_TAB)if(t!=null&&t.getDisplayName().getString().equals("Simply Mirrors"))tab=true;
        int error=glGetError();Path dir=mc.gameDirectory.toPath().resolve("visual-evidence");Files.createDirectories(dir);
        VisualCapture.save(((com.mojang.blaze3d.opengl.GlTexture)mc.getMainRenderTarget().getColorTexture()).glId(),mc.getMainRenderTarget().width,mc.getMainRenderTarget().height,dir.resolve("mirror.png"));
        String json="{\"passes\":"+MirrorRenderer.completedPasses+",\"glError\":"+error+",\"creativeTab\":"+tab+"}";
        Files.write(dir.resolve("result.json"),json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        System.out.println("SIMPLY_MIRRORS_VISUAL_RESULT "+json);simplymirrors$done=true;mc.stop();
    }
}
