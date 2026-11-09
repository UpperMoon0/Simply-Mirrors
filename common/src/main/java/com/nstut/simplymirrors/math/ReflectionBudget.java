package com.nstut.simplymirrors.math;

/** Pure deterministic GPU budget feedback; no GL context or loader dependencies. */
public final class ReflectionBudget {
    private ReflectionBudget() {}

    public static double nextScale(double current, double gpuMillis, double budgetMillis) {
        if(!Double.isFinite(current) || !Double.isFinite(gpuMillis) ||
           !Double.isFinite(budgetMillis) || budgetMillis <= 0)
            throw new IllegalArgumentException("Invalid adaptive reflection budget");
        if(gpuMillis > budgetMillis * 1.2) return Math.max(0.5, current - 0.25);
        if(gpuMillis < budgetMillis * 0.55) return Math.min(1.0, current + 0.25);
        return current;
    }
}
