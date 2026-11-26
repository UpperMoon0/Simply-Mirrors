package com.nstut.simplymirrors.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import static org.lwjgl.opengl.GL33.*;

/**
 * Optional GPU timestamp attribution for Iris/Oculus fullscreen shader quads.
 *
 * Reflection GPU-stage totals alone cannot identify the costly program. Query
 * timestamps never block the draw: results are polled on subsequent frames.
 * This profiler is disabled completely outside -Dsimplymirrors.gpuProfile=true.
 */
public final class ShaderQuadGpuProfiler {
    private static final boolean ENABLED = Boolean.getBoolean("simplymirrors.gpuProfile");
    private static final int REPORT_INTERVAL = 180;
    private static final ArrayDeque<Active> ACTIVE = new ArrayDeque<>();
    private static final ArrayDeque<Sample> PENDING = new ArrayDeque<>();
    private static final Map<String, List<Double>> SAMPLES = new HashMap<>();
    private static int dropped;

    private ShaderQuadGpuProfiler() { }

    public static void begin() {
        if (!ENABLED || MirrorRenderer.redirect == null) return;
        int program = glGetInteger(GL_CURRENT_PROGRAM);
        String name = ReflectionScissor.profileProgramName(program);
        if (name == null) name = "unknown-" + program;
        int start = glGenQueries();
        glQueryCounter(start, GL_TIMESTAMP);
        ACTIVE.push(new Active(name, start));
    }

    public static void end() {
        if (!ENABLED || MirrorRenderer.redirect == null || ACTIVE.isEmpty()) return;
        Active active = ACTIVE.pop();
        int end = glGenQueries();
        glQueryCounter(end, GL_TIMESTAMP);
        PENDING.addLast(new Sample(active.name, active.start, end));
    }

    public static void poll() {
        if (!ENABLED) return;
        while (!PENDING.isEmpty()) {
            Sample sample = PENDING.peekFirst();
            if (glGetQueryObjecti(sample.end, GL_QUERY_RESULT_AVAILABLE) == GL_FALSE) break;
            PENDING.removeFirst();
            try {
                long start = glGetQueryObjectui64(sample.start, GL_QUERY_RESULT);
                long end = glGetQueryObjectui64(sample.end, GL_QUERY_RESULT);
                double elapsed = (end - start) / 1_000_000.0;
                if (elapsed < 0 || elapsed > 1000) continue;
                List<Double> values = SAMPLES.computeIfAbsent(sample.name, k -> new ArrayList<>());
                values.add(elapsed);
                if (values.size() >= REPORT_INTERVAL) {
                    Collections.sort(values);
                    System.out.printf(Locale.ROOT,
                            "SIMPLY_MIRRORS_SHADER_QUAD_PROFILE program=%s samples=%d gpuMedianMs=%.3f gpuP95Ms=%.3f dropped=%d%n",
                            sample.name, values.size(), values.get(values.size() / 2),
                            values.get(Math.min(values.size() - 1, (int) Math.ceil(values.size() * 0.95) - 1)),
                            dropped);
                    values.clear();
                }
            } finally {
                sample.release();
            }
        }
        // GPU profiling must never accumulate unbounded driver query objects.
        // Never block rendering with glFinish or synchronous query waits.
        while (PENDING.size() > 2048) {
            PENDING.removeFirst().release();
            dropped++;
        }
    }

    public static void reset() {
        if (!ENABLED) return;
        while (!ACTIVE.isEmpty()) glDeleteQueries(ACTIVE.pop().start);
        while (!PENDING.isEmpty()) PENDING.removeFirst().release();
        SAMPLES.clear();
        dropped = 0;
    }

    private static final class Active {
        final String name;
        final int start;
        Active(String name, int start) { this.name = name; this.start = start; }
    }

    private static final class Sample {
        final String name;
        final int start, end;
        Sample(String name, int start, int end) {
            this.name = name; this.start = start; this.end = end;
        }
        void release() { glDeleteQueries(start); glDeleteQueries(end); }
    }
}