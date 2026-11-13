package com.nstut.simplymirrors.visualsupport;

import com.nstut.simplymirrors.client.MirrorRenderer;
import com.nstut.simplymirrors.visualsupport.ShaderProbe;
import com.nstut.simplymirrors.visualsupport.VisualCapture;
import com.nstut.simplymirrors.visualsupport.VisualScene;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
    private static boolean motionFirst, motionSecond;
    private static String pendingCaptureJson;
    private static int parityFrames;
    public static boolean parityCapture() { return pendingCaptureJson != null; }

    private FpsBenchmarkProbe() { }

    public static void prepareWindow(Minecraft mc) {
        // Keep the benchmark on the current GPU without obscuring the user's screen.
        // The GLFW window remains non-minimized, and actual shader passes still run.
        if (!relocated && Boolean.getBoolean("simplymirrors.fpsOffscreen")) {
            long handle = mc.getWindow().getWindow();
            // The --width/--height launch flags create a decorated Windows
            // outer window, not necessarily a same-size framebuffer (a 1080
            // request previously produced 1061 pixels). Use only this
            // disposable private test window, never the player's client.
            int nativeWidth = Integer.getInteger("simplymirrors.fpsExpectedWidth", 0);
            int nativeHeight = Integer.getInteger("simplymirrors.fpsExpectedHeight", 0);
            if (nativeWidth > 0 && nativeHeight > 0) {
                GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE);
                GLFW.glfwSetWindowSize(handle, nativeWidth, nativeHeight);
            }
            GLFW.glfwSetWindowPos(handle, -15000, -15000);
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
        // Encode trial number in the tens digit to support alternating repeated rounds.
        int kind = requested % 10;
        if (requested < 7 || (kind != 7 && kind != 8 && kind != 9))
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
            motionFirst = motionSecond = false;
            pendingCaptureJson = null;
            parityFrames = 0;
            System.out.println("SIMPLY_MIRRORS_FPS_STAGE scenario=" + scene);
        }

        // Optional deterministic camera sweep stresses history and disocclusion.
        // The same sweep is applied to the no-mirror paired baseline.
        // Static fixtures remain the default for historical comparability.
        boolean moving = Boolean.getBoolean("simplymirrors.fpsCameraMotion");
        double[] eye = VisualScene.eye(0);
        double phase = finished ? 0 : (now - entered) * (2.0 * Math.PI / 6_000_000_000.0);
        double x = eye[0] + (moving ? 0.15 * Math.sin(phase) : 0);
        double y = eye[1] - 1.62;
        double z = eye[2] + (moving ? 0.05 * Math.cos(phase) : 0);
        float yaw = moving ? (float)(4.0 * Math.sin(phase)) : 0f;
        float pitch = moving ? (float)(1.25 * Math.cos(phase)) : 0f;
        mc.player.getAbilities().flying = true;
        mc.player.setPos(x, y, z);
        mc.player.xo = x;
        mc.player.yo = y;
        mc.player.zo = z;
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
        mc.player.yRotO = yaw;
        mc.player.xRotO = pitch;
        mc.player.yBodyRot = yaw;
        mc.player.yHeadRot = yaw;
        mc.player.yBodyRotO = yaw;
        mc.player.yHeadRotO = yaw;
        if (finished) {
            // Freeze only the disposable fixture's animation after timed motion
            // measurement. A/B captures must not compare different idle-arm poses.
            mc.player.tickCount = 0;
            if (pendingCaptureJson != null && ++parityFrames >= 64) {
                int parityError=glGetError();
                if (parityError != GL_NO_ERROR)
                    throw new IllegalStateException("Parity OpenGL error: " + parityError);
                Path evidence=home.resolve("fps-evidence");
                VisualCapture.save(mc.getMainRenderTarget().getColorTextureId(),
                        mc.getMainRenderTarget().width,mc.getMainRenderTarget().height,
                        evidence.resolve("scene-" + scene + ".png"));
                // Publish only complete JSON: the Python reader polls the final path.
                // A direct write exposes an empty/partial file under host paging.
                Path result = evidence.resolve("scene-" + scene + ".json");
                Path pending = evidence.resolve("scene-" + scene + ".json.pending");
                Files.write(pending, pendingCaptureJson.getBytes(StandardCharsets.UTF_8));
                Files.move(pending, result, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                pendingCaptureJson=null;
                System.out.println("SIMPLY_MIRRORS_FPS_PARITY_CAPTURE scenario="+scene+" settledFrames=64 fixedAnimation=true");
            }
            return;
        }

        // Capture at opposing sweep phases entirely inside warmup, so the
        // slow readback never contaminates measured frame intervals.
        if (moving && kind != 7 && (!motionFirst || !motionSecond)) {
            long elapsed = now - entered;
            if ((!motionFirst && elapsed >= 2_000_000_000L) ||
                    (motionFirst && !motionSecond && elapsed >= 4_000_000_000L)) {
                Path evidence = home.resolve("fps-evidence");
                Files.createDirectories(evidence);
                String name = "scene-" + scene + (motionFirst ? "-motion-b.png" : "-motion-a.png");
                VisualCapture.save(mc.getMainRenderTarget().getColorTextureId(),
                        mc.getMainRenderTarget().width, mc.getMainRenderTarget().height,
                        evidence.resolve(name));
                if (motionFirst) motionSecond = true;
                else motionFirst = true;
            }
        }

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
        if (Boolean.getBoolean("simplymirrors.fpsCameraMotion") && kind != 7 &&
                (!motionFirst || !motionSecond))
            throw new IllegalStateException("Missing opposing-phase camera motion captures");
        if (durations.size() < 30) throw new IllegalStateException("Too few real frames: " + durations.size());
        if (!shader) throw new IllegalStateException("Complementary shader pack was not active");
        if (iconified != GLFW.GLFW_FALSE) throw new IllegalStateException("Benchmark window was minimized");
        if (error != GL_NO_ERROR) throw new IllegalStateException("OpenGL error: " + error);
        long totalPasses = 0;
        final long expectedPassesPerFrame = kind == 7 ? 0L : 1L;
        for (int i = 0; i < reflectionPasses.size(); i++) {
            long count = reflectionPasses.get(i);
            if (count != expectedPassesPerFrame)
                throw new IllegalStateException("Scene " + scene + " frame " + i +
                        " rendered " + count + " reflection passes; expected " +
                        expectedPassesPerFrame);
            totalPasses += count;
        }

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
        // Cadence measurement remains moving-camera. Readback happens only
        // after a fixed camera/animation pose and identical TAA settling frames.
        pendingCaptureJson=json.toString();
        System.out.println("SIMPLY_MIRRORS_FPS_RESULT scenario=" + scene
                + " samples=" + durations.size() + " fps=" + averageFps
                + " p95ms=" + p95Ms + " passes=" + totalPasses);
    }
}
