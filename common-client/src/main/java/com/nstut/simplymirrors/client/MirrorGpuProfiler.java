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
        if (current != null) current.release();
        current = new Frame();
        mark(0);
    }

    public static void mark(int stage) {
        if (!ENABLED || current == null || stage < 0 || stage > 6) return;
        if (current.queries[stage] != 0) return;
        int query = glGenQueries();
        current.queries[stage] = query;
        glQueryCounter(query, GL_TIMESTAMP);
        current.cpu[stage] = System.nanoTime();
        if (stage == 6) {
            PENDING.addLast(current);
            current = null;
        }
    }

    public static void shadowBegin() { mark(2); }
    public static void shadowEnd() { mark(3); }

    private static void poll() {
        while (!PENDING.isEmpty()) {
            Frame first = PENDING.peekFirst();
            if (glGetQueryObjecti(first.queries[6], GL_QUERY_RESULT_AVAILABLE) == GL_FALSE) break;
            PENDING.removeFirst();
            try {
                long[] ticks = new long[7];
                boolean valid = true;
                for (int i = 0; i < 7; i++) {
                    if (first.queries[i] == 0) { valid = false; break; }
                    ticks[i] = glGetQueryObjectui64(first.queries[i], GL_QUERY_RESULT);
                    if (i > 0 && ticks[i] < ticks[i - 1]) valid = false;
                }
                if (!valid) continue;
                double[] values = new double[] {
                    ms(ticks[1]-ticks[0]), // begin snapshot
                    ms(ticks[4]-ticks[1]), // world shaders
                    ms(ticks[3]-ticks[2]), // Oculus shadow+prepare
                    ms(ticks[5]-ticks[4]), // restore history
                    ms(ticks[6]-ticks[5]), // composite
                    ms(ticks[6]-ticks[0])  // entire reflection
                };
                SAMPLES.add(values);
                processed++;
                if (SAMPLES.size() >= 90) {
                    System.out.printf(Locale.ROOT,
                        "SIMPLY_MIRRORS_GPU_PROFILE frames=%d gpuCaptureMs=%.3f gpuWorldMs=%.3f gpuShadowsAndPrepareMs=%.3f gpuRestoreMs=%.3f gpuCompositeMs=%.3f gpuTotalMs=%.3f%n",
                        processed, median(0), median(1), median(2), median(3), median(4), median(5));
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
    private static double ms(long ns) { return ns / 1_000_000.0; }

    public static void reset() {
        if (!ENABLED) return;
        if (current != null) { current.release(); current = null; }
        while (!PENDING.isEmpty()) PENDING.removeFirst().release();
        SAMPLES.clear();
    }

    private static final class Frame {
        final int[] queries = new int[7];
        final long[] cpu = new long[7];
        void release() {
            for (int query : queries) if (query != 0) glDeleteQueries(query);
        }
    }
}
