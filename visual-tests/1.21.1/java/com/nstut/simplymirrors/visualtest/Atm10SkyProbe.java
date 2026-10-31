package com.nstut.simplymirrors.visualtest;

import com.nstut.simplymirrors.client.MirrorRenderer;
import com.nstut.simplymirrors.visualsupport.ShaderProbe;
import com.nstut.simplymirrors.visualsupport.VisualCapture;
import com.nstut.simplymirrors.visualsupport.VisualScene;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.GameType;
import org.lwjgl.glfw.GLFW;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.lwjgl.opengl.GL33.*;

/**
 * Development-only in-process visual capture for the complete ATM10 Sky client.
 * 
 * A separate ephemeral native server prepares six sealed rooms in a disposable
 * world before the client starts. The integrated server loads that world with
 * the actual modpack registries. No live server, launcher profile, or personal
 * world is accessed.
 */
public final class Atm10SkyProbe {
    private static final String WORLD = "atm10-sky-mirror-fixture";
    private static final int OFFSET = 48;
    private static int frames, scene = -1, sceneFrames, mirrorFrames;
    private static long firstPass;
    private static boolean loading, captured, done;

    private Atm10SkyProbe() {}

    public static void tick(Minecraft mc) throws Exception {
        if (done) return;
        if (frames++ == 0) {
            mc.options.onboardAccessibility = false;
            mc.options.pauseOnLostFocus = false;
            mc.options.renderDistance().set(4);
            mc.options.simulationDistance().set(4);
            mc.options.bobView().set(false);
            mc.options.framerateLimit().set(30);
            mc.options.hideGui = true;
            mc.options.enableVsync().set(false);
            if (mc.getWindow().isFullscreen()) mc.getWindow().toggleFullScreen();
            GLFW.glfwHideWindow(mc.getWindow().getWindow());
            System.out.println("ATM10_SKY_VISUAL_HIDDEN_WINDOW");
        }
        if (mc.player == null || mc.level == null) {
            if (frames % 300 == 0) System.out.println("ATM10_SKY_VISUAL_STAGE " +
                    (mc.screen == null ? "none" : mc.screen.getClass().getName()));
            if (mc.getOverlay() == null && mc.screen instanceof AccessibilityOnboardingScreen)
                mc.setScreen(new TitleScreen());
            if (!loading && mc.getOverlay() == null && mc.screen instanceof TitleScreen) {
                loading = true;
                System.out.println("ATM10_SKY_VISUAL_OPEN " + WORLD);
                mc.createWorldOpenFlows().openWorld(WORLD, () -> {
                    System.out.println("ATM10_SKY_VISUAL_WORLD_OPEN_ABORTED");
                });
            }
            // Only the disposable world is opened, so dismiss its known
            // datapack/experimental feature confirmation if the pack prompts.
            if (loading && mc.screen != null && !(mc.screen instanceof TitleScreen)) {
                for (var widget : mc.screen.children()) {
                    if (widget instanceof Button button) {
                        String text = button.getMessage().getString().toLowerCase(Locale.ROOT);
                        if (text.contains("proceed") || text.contains("i know what") ||
                                text.contains("continue with") || text.contains("load anyway")) {
                            button.onPress();
                            break;
                        }
                    }
                }
            }
            return;
        }
        if (mc.getSingleplayerServer() == null) {
            throw new IllegalStateException("ATM10 Sky acceptance requires an integrated server");
        }
        if (mc.screen != null) mc.setScreen(null);
        int requested = VisualScene.index(mc.gameDirectory.toPath());
        if (requested < 0) return;
        if (requested == 6) {
            done = true;
            System.out.println("ATM10_SKY_VISUAL_COMPLETE");
            mc.stop();
            return;
        }
        if (requested > 5) throw new IllegalStateException("Invalid face index " + requested);
        if (scene != requested) {
            scene = requested;
            sceneFrames = 0;
            mirrorFrames = 0;
            firstPass = MirrorRenderer.completedPasses;
            captured = false;
            double[] eye = VisualScene.eye(requested);
            int index = requested;
            MinecraftServer server = mc.getSingleplayerServer();
            java.util.UUID playerId = mc.player.getUUID();
            server.execute(() -> {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player != null) {
                    player.setGameMode(GameType.CREATIVE);
                    player.teleportTo(server.overworld(), eye[0] + index * OFFSET,
                            eye[1] - 1.62, eye[2],
                            VisualScene.yaw(index), VisualScene.pitch(index));
                }
            });
            System.out.println("ATM10_SKY_VISUAL_SCENE " + requested);
        }
        sceneFrames++;
        // Let the integrated server complete its authoritative teleport before
        // touching local camera interpolation (avoids false empty captures).
        if (sceneFrames < 50) return;
        if (MirrorRenderer.completedPasses > firstPass) mirrorFrames++;
        int phase = mirrorFrames / 20;
        float yaw = VisualScene.yaw(requested) + (phase == 1 ? 20 : phase == 2 ? -20 : 0);
        float base = VisualScene.pitch(requested);
        float pitch = base + (phase == 3 ? (base > 0 ? -20 : 20) :
                              phase == 4 ? (base < 0 ? 20 : -20) : 0);
        double[] eye = VisualScene.eye(requested);
        double px = eye[0] + requested * OFFSET, py = eye[1] - 1.62, pz = eye[2];
        mc.player.setPos(px, py, pz);
        mc.player.xo = px; mc.player.yo = py; mc.player.zo = pz;
        mc.player.setYRot(yaw); mc.player.setXRot(pitch);
        mc.player.yRotO = yaw; mc.player.xRotO = pitch;
        mc.player.yBodyRot = yaw; mc.player.yHeadRot = yaw;
        if (captured) return;
        if ((MirrorRenderer.completedPasses - firstPass < 120 || mirrorFrames < 120)
                && sceneFrames < 1800) return;

        boolean tab = false;
        for (CreativeModeTab item : BuiltInRegistries.CREATIVE_MODE_TAB)
            if (item != null && item.getDisplayName().getString().equals("Simply Mirrors"))
                tab = true;
        int error = glGetError();
        Path dir = mc.gameDirectory.toPath().resolve("visual-evidence");
        Files.createDirectories(dir);
        VisualCapture.save(mc.getMainRenderTarget().getColorTextureId(),
                mc.getMainRenderTarget().width, mc.getMainRenderTarget().height,
                dir.resolve("mirror-" + requested + ".png"));
        String json = "{\"passes\":" + (MirrorRenderer.completedPasses - firstPass) +
                ",\"angleFrames\":" + mirrorFrames +
                ",\"glError\":" + error + ",\"creativeTab\":" + tab +
                ",\"shaderPackActive\":" + ShaderProbe.active() +
                ",\"integratedWorld\":true}";
        Files.writeString(dir.resolve("result-" + requested + ".json"), json + "\n",
                StandardCharsets.UTF_8);
        System.out.println("ATM10_SKY_VISUAL_RESULT " + requested + " " + json);
        captured = true;
    }
}
