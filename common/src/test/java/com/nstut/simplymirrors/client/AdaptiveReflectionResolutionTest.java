package com.nstut.simplymirrors.client;

import static org.junit.Assert.assertEquals;
import com.nstut.simplymirrors.math.ReflectionBudget;
import org.junit.Test;

/** No GL context required: test the GPU-cost feedback policy in isolation. */
public class AdaptiveReflectionResolutionTest {
    @Test
    public void downscalesWhenReflectionExceedsGpuBudget() {
        assertEquals(0.5, ReflectionBudget.nextScale(0.75, 4.0, 3.0), 0.001);
    }

    @Test
    public void recoversQualityWithExcessHeadroom() {
        assertEquals(1.0, ReflectionBudget.nextScale(0.75, 1.0, 3.0), 0.001);
    }

    @Test
    public void doesNotOscillateNearBudget() {
        assertEquals(0.75, ReflectionBudget.nextScale(0.75, 3.1, 3.0), 0.001);
        assertEquals(0.75, ReflectionBudget.nextScale(0.75, 1.9, 3.0), 0.001);
    }

    @Test
    public void clampsResolutionLimits() {
        assertEquals(0.5, ReflectionBudget.nextScale(0.5, 20, 3.0), 0.001);
        assertEquals(1.0, ReflectionBudget.nextScale(1.0, 0.3, 3.0), 0.001);
    }
}
