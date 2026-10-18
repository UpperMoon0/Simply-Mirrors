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
    @Unique private int simplymirrors$mirrorFrames;
    @Unique private boolean simplymirrors$done;
    @Unique private boolean simplymirrors$joining;
    @Inject(method="runTick",at=@At("TAIL"))
    private void simplymirrors$probe(CallbackInfo ci) throws Exception {
        if(simplymirrors$done)return;Minecraft mc=Minecraft.getInstance();
        if(simplymirrors$frames++==0) {mc.options.onboardAccessibility=false;mc.options.pauseOnLostFocus=false;mc.options.renderDistance().set(4);mc.options.bobView().set(false);mc.options.framerateLimit().set(30);mc.options.hideGui=true;}
        if(mc.player==null||mc.level==null) {
            if(simplymirrors$frames%300==0)System.out.println("SIMPLY_MIRRORS_VISUAL_STAGE "+(mc.screen==null?"none":mc.screen.getClass().getName()));
            if(mc.getOverlay()==null&&mc.screen instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen)mc.setScreen(new TitleScreen());
            if(!simplymirrors$joining&&mc.getOverlay()==null&&mc.screen instanceof TitleScreen) {
                simplymirrors$joining=true;String port=System.getProperty("simplymirrors.visualPort");String address="127.0.0.1:"+port;
                System.out.println("SIMPLY_MIRRORS_VISUAL_CONNECT "+address);ConnectScreen.startConnecting(new TitleScreen(),mc,ServerAddress.parseString(address),new ServerData("Mirror test",address,false),false);
            }
            return;
        }
        if(MirrorRenderer.completedPasses>0)simplymirrors$mirrorFrames++;
        int phase=simplymirrors$mirrorFrames/20;
        float yaw=phase==1?20:phase==2?-20:0;
        float pitch=phase==3?20:phase==4?-20:0;
        mc.player.setPos(.5,65,-3.5);mc.player.xo=.5;mc.player.yo=65;mc.player.zo=-3.5;
        mc.player.setYRot(yaw);mc.player.setXRot(pitch);mc.player.yRotO=yaw;mc.player.xRotO=pitch;mc.player.yBodyRot=yaw;mc.player.yHeadRot=yaw;
        if((MirrorRenderer.completedPasses<120||simplymirrors$mirrorFrames<120)&&simplymirrors$frames<1800)return;
        boolean tab=false;for(CreativeModeTab t:BuiltInRegistries.CREATIVE_MODE_TAB)if(t!=null&&t.getDisplayName().getString().equals("Simply Mirrors"))tab=true;
        int error=glGetError();Path dir=mc.gameDirectory.toPath().resolve("visual-evidence");Files.createDirectories(dir);
        VisualCapture.save(mc.getMainRenderTarget().getColorTextureId(),mc.getMainRenderTarget().width,mc.getMainRenderTarget().height,dir.resolve("mirror.png"));
        String json="{\"passes\":"+MirrorRenderer.completedPasses+",\"angleFrames\":"+simplymirrors$mirrorFrames+",\"glError\":"+error+",\"creativeTab\":"+tab+"}";
        Files.write(dir.resolve("result.json"),json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        System.out.println("SIMPLY_MIRRORS_VISUAL_RESULT "+json);simplymirrors$done=true;mc.stop();
    }
}
