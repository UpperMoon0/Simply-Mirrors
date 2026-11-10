package com.nstut.simplymirrors.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import static org.lwjgl.opengl.GL33.*;

/**
 * Optional real driver GPU timestamp probes for the entire reflected pass.
 *
 * GL_TIMESTAMP queries are non-blocking until all the result-availability
 * flags are set; polling avoids glFinish and blocking the render thread.
 * This probe is completely disabled in ordinary play and in release CI.
 *
 * Timeline: [0] reflection start, [1] shader history capture,
 * [2] Oculus shadow+prepare begin, [3] shadow+prepare end,
 * [4] world render end, [5] history restoration, [6] compositing.
 * [7..10] optional shader pipeline stage boundaries: translucent begin/end,
 * final begin/end. These extra samples never change game rendering.
 */
public final class MirrorGpuProfiler {
    private static final boolean ENABLED = Boolean.getBoolean("simplymirrors.gpuProfile");
    private static final ArrayDeque<Frame> PENDING = new ArrayDeque<Frame>();
    private static final List<double[]> SAMPLES = new ArrayList<double[]>();
    private static Frame current;
    private static int processed;

    private MirrorGpuProfiler() { }

    public static void begin() {
        if (!ENABLED) return;
        poll();
        ShaderPassGpuTiming.beginFrame();
        if (current != null) current.release();
        current = new Frame();
        mark(0);
    }

    public static void mark(int stage) {
        if (!ENABLED || current == null || stage < 0 || stage > 10) return;
        if (current.queries[stage] != 0) return;
        int query = glGenQueries();
        current.queries[stage] = query;
        glQueryCounter(query, GL_TIMESTAMP);
        current.cpu[stage] = System.nanoTime();
        if (stage == 6) {
            ShaderPassGpuTiming.endFrame();
            PENDING.addLast(current);
            current = null;
        }
    }

    public static void shadowBegin() { mark(2); }
    public static void shadowEnd() { mark(3); }
    public static void translucentBegin() { mark(7); }
    public static void translucentEnd() { mark(8); }
    public static void finalizeBegin() { mark(9); }
    public static void finalizeEnd() { mark(10); }

    private static void poll() {
        while (!PENDING.isEmpty()) {
            Frame first = PENDING.peekFirst();
            if (glGetQueryObjecti(first.queries[6], GL_QUERY_RESULT_AVAILABLE) == GL_FALSE) break;
            PENDING.removeFirst();
            try {
                long[] ticks = new long[11];
                boolean valid = true;
                for (int i = 0; i < 11; i++) {
                    if (first.queries[i] == 0) {
                        if (i < 7) valid = false;
                        continue;
                    }
                    // The last composite query is complete before the others are read.
                    ticks[i] = glGetQueryObjectui64(first.queries[i], GL_QUERY_RESULT);
                }
                for (int i = 1; i <= 6; i++) if (ticks[i] < ticks[i - 1]) valid = false;
                boolean segments = true;
                for (int i = 7; i <= 10; i++) if (ticks[i] == 0) segments = false;
                if (segments && !(ticks[1] <= ticks[7] && ticks[7] <= ticks[8] &&
                        ticks[8] <= ticks[9] && ticks[9] <= ticks[10] && ticks[10] <= ticks[4]))
                    segments = false;
                if (!valid) continue;
                double[] values = new double[] {
                    ms(ticks[1]-ticks[0]), // begin snapshot
                    ms(ticks[4]-ticks[1]), // world shaders
                    ms(ticks[3]-ticks[2]), // Oculus shadow+prepare
                    ms(ticks[5]-ticks[4]), // restore history
                    ms(ticks[6]-ticks[5]), // composite
                    ms(ticks[6]-ticks[0]), // entire reflection
                    segments ? ms(ticks[7]-ticks[1]) : Double.NaN, // opaque + early
                    segments ? ms(ticks[8]-ticks[7]) : Double.NaN, // begin translucents
                    segments ? ms(ticks[9]-ticks[8]) : Double.NaN, // translucent work
                    segments ? ms(ticks[10]-ticks[9]) : Double.NaN, // final shader stage
                    ms(first.cpu[1]-first.cpu[0]), // CPU history capture and setup
                    ms(first.cpu[4]-first.cpu[1]), // CPU reflected world rendering
                    ms(first.cpu[5]-first.cpu[4]), // CPU history restore and rebind
                    ms(first.cpu[6]-first.cpu[5]), // CPU mirror composite
                    ms(first.cpu[6]-first.cpu[0]), // CPU total reflection overhead
                    segments ? ms(first.cpu[10]-first.cpu[9]) : Double.NaN // CPU final shader
                };
                SAMPLES.add(values);
                processed++;
                if (SAMPLES.size() >= 90) {
                    System.out.printf(Locale.ROOT,
                        "SIMPLY_MIRRORS_GPU_PROFILE frames=%d gpuCaptureMs=%.3f gpuWorldMs=%.3f gpuShadowsAndPrepareMs=%.3f gpuRestoreMs=%.3f gpuCompositeMs=%.3f gpuTotalMs=%.3f gpuOpaqueEarlyMs=%.3f gpuDeferredBeginMs=%.3f gpuTranslucentMs=%.3f gpuFinalizeMs=%.3f cpuCaptureMs=%.3f cpuWorldMs=%.3f cpuRestoreMs=%.3f cpuCompositeMs=%.3f cpuTotalMs=%.3f cpuFinalizeMs=%.3f%n",
                        processed, median(0), median(1), median(2), median(3), median(4), median(5),
                        medianOrMissing(6), medianOrMissing(7), medianOrMissing(8), medianOrMissing(9),
                        median(10), median(11), median(12), median(13), median(14),
                        medianOrMissing(15));
                    SAMPLES.clear();
                }
            } finally {
                first.release();
            }
        }
        // Unexpectedly unavailable GPU queries must not consume indefinite VRAM.
        if (PENDING.size() > 96) throw new IllegalStateException(
                "OpenGL timestamp queries never completed after 96 reflected frames");
    }

    private static double median(int column) {
        List<Double> series = new ArrayList<Double>();
        for (double[] values : SAMPLES) series.add(values[column]);
        Collections.sort(series);
        return series.get(series.size()/2);
    }
    private static double medianOrMissing(int column) {
        List<Double> series = new ArrayList<Double>();
        for (double[] values : SAMPLES) if (!Double.isNaN(values[column])) series.add(values[column]);
        if (series.isEmpty()) return -1.0;
        Collections.sort(series);
        return series.get(series.size()/2);
    }
    private static double ms(long ns) { return ns / 1_000_000.0; }

    public static void reset() {
        if (!ENABLED) return;
        if (current != null) { current.release(); current = null; }
        while (!PENDING.isEmpty()) PENDING.removeFirst().release();
        SAMPLES.clear();
        ShaderPassGpuTiming.reset();
    }

    private static final class Frame {
        final int[] queries = new int[11];
        final long[] cpu = new long[11];
        void release() {
            for (int query : queries) if (query != 0) glDeleteQueries(query);
        }
    }
}
