package com.nstut.simplymirrors.visualtest;

import com.nstut.simplymirrors.client.MirrorRenderer;
import com.nstut.simplymirrors.visualsupport.ShaderProbe;
import com.nstut.simplymirrors.visualsupport.VisualCapture;
import com.nstut.simplymirrors.visualsupport.VisualScene;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import static org.lwjgl.opengl.GL33.*;

/**
 * Test-only real Minecraft frame-cadence sampler, invoked after rendered frames.
 * Measures end-to-end frame intervals, not synthetic compositor or server TPS.
 * No measurements are made while the test client changes its world fixture.
 */
public final class FpsBenchmarkProbe {
    private static final long WARMUP_NS = 8_000_000_000L;
    private static final long MEASURE_NS = 15_000_000_000L;
    private static final List<Long> durations = new ArrayList<Long>();
    private static final List<Long> reflectionPasses = new ArrayList<Long>();
    private static int scene = -1;
    private static long entered, lastFrame, lastPasses;
    private static boolean finished;
    private static boolean relocated;

    private FpsBenchmarkProbe() { }

    public static void prepareWindow(Minecraft mc) {
        // Keep the benchmark on the current GPU without obscuring the user's screen.
        // The GLFW window remains non-minimized, and actual shader passes still run.
        if (!relocated && Boolean.getBoolean("simplymirrors.fpsOffscreen")) {
            GLFW.glfwSetWindowPos(mc.getWindow().getWindow(), -15000, -15000);
            relocated = true;
        }
        mc.options.enableVsync().set(false);
        mc.getWindow().updateVsync(false);
    }

    public static void tick(Minecraft mc) throws Exception {
        Path home = mc.gameDirectory.toPath();
        int requested = VisualScene.index(home);
        if (requested < 0) return;
        if (requested == 6) {
            mc.stop();
            return;
        }
        if (requested != 7 && requested != 8 && requested != 9)
            throw new IllegalStateException("Unknown performance scenario: " + requested);

        long now = System.nanoTime();
        if (scene != requested) {
            scene = requested;
            entered = now;
            lastFrame = 0;
            lastPasses = MirrorRenderer.completedPasses;
            finished = false;
            durations.clear();
            reflectionPasses.clear();
            System.out.println("SIMPLY_MIRRORS_FPS_STAGE scenario=" + scene);
        }

        // All stages share the same fixed camera, world time, and view.
        double[] eye = VisualScene.eye(0);
        double x = eye[0], y = eye[1] - 1.62, z = eye[2];
        mc.player.getAbilities().flying = true;
        mc.player.setPos(x, y, z);
        mc.player.xo = x;
        mc.player.yo = y;
        mc.player.zo = z;
        mc.player.setYRot(0);
        mc.player.setXRot(0);
        mc.player.yRotO = 0;
        mc.player.xRotO = 0;
        mc.player.yBodyRot = 0;
        mc.player.yHeadRot = 0;

        long passes = MirrorRenderer.completedPasses;
        if (lastFrame != 0 && now - entered >= WARMUP_NS && !finished) {
            durations.add(now - lastFrame);
            reflectionPasses.add(passes - lastPasses);
        }
        lastFrame = now;
        lastPasses = passes;
        if (now - entered < WARMUP_NS + MEASURE_NS || finished) return;
        finished = true;
        Path evidence = home.resolve("fps-evidence");
        Files.createDirectories(evidence);
        int error = glGetError();
        boolean shader = ShaderProbe.active();
        int iconified = GLFW.glfwGetWindowAttrib(mc.getWindow().getWindow(), GLFW.GLFW_ICONIFIED);
        String renderer = glGetString(GL_RENDERER);
        String vendor = glGetString(GL_VENDOR);
        int width = mc.getMainRenderTarget().width;
        int height = mc.getMainRenderTarget().height;
        if (durations.size() < 30) throw new IllegalStateException("Too few real frames: " + durations.size());
        if (!shader) throw new IllegalStateException("Complementary shader pack was not active");
        if (iconified != GLFW.GLFW_FALSE) throw new IllegalStateException("Benchmark window was minimized");
        if (error != GL_NO_ERROR) throw new IllegalStateException("OpenGL error: " + error);
        long totalPasses = 0;
        for (Long count : reflectionPasses) totalPasses += count;
        if (scene == 7 && totalPasses != 0) throw new IllegalStateException("Baseline unexpectedly rendered mirror passes");
        if (scene != 7 && totalPasses == 0) throw new IllegalStateException("Mirror rendering did not run");

        List<Long> sorted = new ArrayList<Long>(durations);
        Collections.sort(sorted);
        long sum = 0;
        for (Long d : durations) sum += d;
        double measuredSeconds = sum / 1e9;
        double averageFps = durations.size() / measuredSeconds;
        double p50Ms = sorted.get(sorted.size() / 2) / 1e6;
        double p95Ms = sorted.get((int)Math.ceil(.95 * sorted.size()) - 1) / 1e6;
        StringBuilder json = new StringBuilder(12000);
        json.append("{\n\"scenario\":").append(scene)
            .append(",\n\"shaderActive\":").append(shader)
            .append(",\n\"glRenderer\":\"").append(renderer.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"")
            .append(",\n\"glVendor\":\"").append(vendor.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"")
            .append(",\n\"resolution\":\"").append(width).append("x").append(height).append("\"")
            .append(",\n\"windowIconified\":").append(iconified != GLFW.GLFW_FALSE)
            .append(",\n\"glError\":").append(error)
            .append(",\n\"samples\":").append(durations.size())
            .append(",\n\"reflectionPasses\":").append(totalPasses)
            .append(",\n\"averageFps\":").append(String.format(Locale.ROOT, "%.3f", averageFps))
            .append(",\n\"p50FrameMs\":").append(String.format(Locale.ROOT, "%.3f", p50Ms))
            .append(",\n\"p95FrameMs\":").append(String.format(Locale.ROOT, "%.3f", p95Ms))
            .append(",\n\"frameIntervalsNs\":[");
        for (int i = 0; i < durations.size(); i++) {
            if (i != 0) json.append(',');
            json.append(durations.get(i));
        }
        json.append("],\n\"passesPerFrame\":[");
        for (int i = 0; i < reflectionPasses.size(); i++) {
            if (i != 0) json.append(',');
            json.append(reflectionPasses.get(i));
        }
        json.append("]\n}\n");
        VisualCapture.save(mc.getMainRenderTarget().getColorTextureId(), width, height,
                evidence.resolve("scene-" + scene + ".png"));
        // Write result LAST; the orchestrator treats it as the completed marker.
        Files.write(evidence.resolve("scene-" + scene + ".json"),
                json.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("SIMPLY_MIRRORS_FPS_RESULT scenario=" + scene
                + " samples=" + durations.size() + " fps=" + averageFps
                + " p95ms=" + p95Ms + " passes=" + totalPasses);
    }
}
