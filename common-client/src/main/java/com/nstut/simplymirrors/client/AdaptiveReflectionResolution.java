package com.nstut.simplymirrors.client;

import java.util.ArrayDeque;
import com.nstut.simplymirrors.math.ReflectionBudget;
import static org.lwjgl.opengl.GL33.*;

/**
 * Non-blocking reflection GPU timer and bounded adaptive pixel budget.
 *
 * Only reflection render targets are scaled. The normal world, final mirror
 * composite, and the camera projection stay at native resolution. All queries
 * are read only after GL reports them available; the render thread never waits.
 */
public final class AdaptiveReflectionResolution {
    private static final double MIN_SCALE = 0.5;
    private static final double MAX_SCALE = 1.0;
    private static final boolean AUTO =
        Boolean.parseBoolean(System.getProperty("simplymirrors.adaptiveResolution", "true"));
    private static final double FIXED = fixedScale();
    private static final double BUDGET_MS = budget();
    private static final ArrayDeque<int[]> PENDING = new ArrayDeque<>();
    private static double scale = 0.75;
    private static double averageMs = -1;
    private static int observations;
    private static int start;

    private AdaptiveReflectionResolution() {}

    private static double fixedScale() {
        String value = System.getProperty("simplymirrors.reflectionScale", "").trim();
        if(value.isEmpty()) return -1;
        try {
            double parsed = Double.parseDouble(value);
            if(!Double.isFinite(parsed) || parsed < MIN_SCALE || parsed > MAX_SCALE)
                throw new IllegalArgumentException("Reflection scale must be 0.5..1.0");
            return parsed;
        } catch(NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid reflection scale: " + value, ex);
        }
    }

    private static double budget() {
        String value = System.getProperty("simplymirrors.reflectionBudgetMs", "3.0");
        try {
            double parsed = Double.parseDouble(value);
            if(!Double.isFinite(parsed) || parsed < 1 || parsed > 20)
                throw new IllegalArgumentException("Reflection GPU budget must be 1..20 ms");
            return parsed;
        } catch(NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid reflection GPU budget: " + value, ex);
        }
    }

    public static double scale() {
        return FIXED > 0 ? FIXED : AUTO ? scale : 1.0;
    }

    public static int width(int nativeWidth) {
        return Math.max(1, (int)Math.round(nativeWidth * scale()));
    }

    public static int height(int nativeHeight) {
        return Math.max(1, (int)Math.round(nativeHeight * scale()));
    }

    public static void begin() {
        if(FIXED > 0 || !AUTO) return;
        poll();
        if(start != 0) { glDeleteQueries(start); start = 0; }
        start = glGenQueries();
        glQueryCounter(start, GL_TIMESTAMP);
    }

    public static void end() {
        if(start == 0) return;
        int end = glGenQueries();
        glQueryCounter(end, GL_TIMESTAMP);
        PENDING.addLast(new int[]{start, end});
        start = 0;
        // A stalled driver must not grow an unbounded queue or stall the game.
        while(PENDING.size() > 24) release(PENDING.removeFirst());
    }

    private static void poll() {
        while(!PENDING.isEmpty()) {
            int[] pair = PENDING.peekFirst();
            if(glGetQueryObjecti(pair[1], GL_QUERY_RESULT_AVAILABLE) == GL_FALSE ||
                    glGetQueryObjecti(pair[0], GL_QUERY_RESULT_AVAILABLE) == GL_FALSE)
                break;
            PENDING.removeFirst();
            long elapsed = glGetQueryObjectui64(pair[1], GL_QUERY_RESULT) -
                           glGetQueryObjectui64(pair[0], GL_QUERY_RESULT);
            release(pair);
            if(elapsed <= 0 || elapsed > 250_000_000L) continue;
            observe(elapsed / 1_000_000.0);
        }
    }

    private static void observe(double milliseconds) {
        averageMs = averageMs < 0 ? milliseconds : averageMs * 0.92 + milliseconds * 0.08;
        if(++observations % 32 == 0) scale = ReflectionBudget.nextScale(scale, averageMs, BUDGET_MS);
    }

    private static void release(int[] pair) {
        glDeleteQueries(pair[0]);
        glDeleteQueries(pair[1]);
    }

    public static void reset() {
        if(start != 0) { glDeleteQueries(start); start = 0; }
        while(!PENDING.isEmpty()) release(PENDING.removeFirst());
        scale = 0.75; averageMs = -1; observations = 0;
    }
}
