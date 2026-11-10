package com.nstut.simplymirrors.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import static org.lwjgl.opengl.GL33.*;

/**
 * Sparse, nonblocking per-shader GPU timestamps for reflected full-screen quads.
 *
 * Active only with -Dsimplymirrors.gpuProfile=true. Sample one in 32
 * reflection passes and aggregate medians across eight *completed* samples.
 * All queries are polled for availability; never glFinish or wait on the GPU.
 * No shaders, textures, framebuffers, camera uniforms or render cadence change.
 *
 * GPU timestamp samples are diagnostic, not FPS acceptance evidence. They
 * identify which expensive pass deserves a separate visual-safe experiment.
 */
public final class ShaderPassGpuTiming {
    private static final boolean ENABLED = Boolean.getBoolean("simplymirrors.gpuProfile")
            && !"false".equalsIgnoreCase(System.getProperty(
                    "simplymirrors.shaderPassProfile", "true"));
    private static final int SAMPLE_INTERVAL = 32;
    private static final int SAMPLES_PER_REPORT = 8;
    private static final int MAX_QUADS_PER_FRAME = 96;
    private static final int MAX_PENDING_FRAMES = 8;
    private static final ArrayDeque<Frame> PENDING = new ArrayDeque<Frame>();
    private static final Map<String,List<Double>> SAMPLES = new HashMap<String,List<Double>>();
    private static Frame current;
    private static Probe active;
    private static long reflectedFrames;
    private static int completedSamples;

    private ShaderPassGpuTiming() {}

    static void beginFrame() {
        if (!ENABLED) return;
        poll();
        // A previous failed reflection must not leave query handles behind.
        if (current != null) current.release();
        current = null;
        active = null;
        reflectedFrames++;
        if (reflectedFrames % SAMPLE_INTERVAL == 1) current = new Frame();
    }

    public static void beginQuad() {
        if (!ENABLED || current == null || active != null ||
                current.probes.size() >= MAX_QUADS_PER_FRAME) return;
        String label = ReflectionScissor.currentShaderProgramLabel();
        if (label == null || label.length() > 64 || !label.matches("[A-Za-z0-9_.-]+"))
            return;
        Probe probe = new Probe(label);
        probe.start = glGenQueries();
        glQueryCounter(probe.start, GL_TIMESTAMP);
        active = probe;
    }

    public static void endQuad() {
        if (!ENABLED || current == null || active == null) return;
        active.end = glGenQueries();
        glQueryCounter(active.end, GL_TIMESTAMP);
        current.probes.add(active);
        active = null;
    }

    static void endFrame() {
        if (!ENABLED || current == null) return;
        // If a shader throws during drawing, discard its unmatched start.
        if (active != null) {
            if (active.start != 0) glDeleteQueries(active.start);
            active = null;
        }
        if (current.probes.isEmpty()) current.release();
        else PENDING.addLast(current);
        current = null;
        // Do not retain unbounded driver query handles under GPU overload.
        while (PENDING.size() > MAX_PENDING_FRAMES)
            PENDING.removeFirst().release();
    }

    static void reset() {
        if (!ENABLED) return;
        if (current != null) current.release();
        current = null;
        if (active != null && active.start != 0) glDeleteQueries(active.start);
        active = null;
        while (!PENDING.isEmpty()) PENDING.removeFirst().release();
        SAMPLES.clear();
        completedSamples = 0;
    }

    private static void poll() {
        while (!PENDING.isEmpty()) {
            Frame frame = PENDING.peekFirst();
            Probe last = frame.probes.get(frame.probes.size() - 1);
            if (glGetQueryObjecti(last.end, GL_QUERY_RESULT_AVAILABLE) == GL_FALSE)
                break;
            PENDING.removeFirst();
            try {
                // Sum repeated draws of one named program *within a reflection*.
                // Averages of per-draw costs would undercount multipass shaders.
                Map<String,Double> perFrame = new HashMap<String,Double>();
                for (Probe probe : frame.probes) {
                    long start = glGetQueryObjectui64(probe.start, GL_QUERY_RESULT);
                    long end = glGetQueryObjectui64(probe.end, GL_QUERY_RESULT);
                    if (end < start || end - start > 1_000_000_000L) continue;
                    Double previous = perFrame.get(probe.name);
                    perFrame.put(probe.name, (previous == null ? 0.0 : previous)
                            + (end - start) / 1_000_000.0);
                }
                if (perFrame.isEmpty()) continue;
                for (Map.Entry<String,Double> entry : perFrame.entrySet()) {
                    List<Double> times = SAMPLES.get(entry.getKey());
                    if (times == null) {
                        times = new ArrayList<Double>();
                        SAMPLES.put(entry.getKey(), times);
                    }
                    times.add(entry.getValue());
                }
                if (++completedSamples >= SAMPLES_PER_REPORT) {
                    report();
                    SAMPLES.clear();
                    completedSamples = 0;
                }
            } finally {
                frame.release();
            }
        }
    }

    private static void report() {
        // Deterministic descending order makes the actual hot shader clear.
        List<Map.Entry<String,List<Double>>> entries =
                new ArrayList<Map.Entry<String,List<Double>>>(SAMPLES.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<String,List<Double>>>() {
            @Override public int compare(Map.Entry<String,List<Double>> a,
                                         Map.Entry<String,List<Double>> b) {
                return Double.compare(median(b.getValue()), median(a.getValue()));
            }
        });
        StringBuilder message = new StringBuilder("SIMPLY_MIRRORS_SHADER_PASS_GPU frames=")
                .append(completedSamples);
        int count = 0;
        for (Map.Entry<String,List<Double>> entry : entries) {
            if (++count > 24) break;
            message.append(' ').append(entry.getKey()).append("Ms=")
                    .append(String.format(Locale.ROOT,"%.3f",median(entry.getValue())));
        }
        System.out.println(message);
    }

    private static double median(List<Double> original) {
        List<Double> copy = new ArrayList<Double>(original);
        Collections.sort(copy);
        return copy.get(copy.size() / 2);
    }

    private static final class Frame {
        final List<Probe> probes = new ArrayList<Probe>();
        void release() {
            for (Probe probe : probes) {
                if (probe.start != 0) glDeleteQueries(probe.start);
                if (probe.end != 0) glDeleteQueries(probe.end);
            }
            probes.clear();
        }
    }
    private static final class Probe {
        final String name;
        int start, end;
        Probe(String name) { this.name = name; }
    }
}
