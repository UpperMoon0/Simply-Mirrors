package com.nstut.simplymirrors.math;

import org.junit.Test;
import static org.junit.Assert.*;

public class ShaderEarlyCompositeCropTest {
    @Test public void onlyRecognizedCompositeProgramIsEligible() {
        assertTrue(ShaderEarlyCompositeCrop.isEligible("composite"));
        for (String name : new String[]{"composite1", "deferred1", "final", "COMPOSITE", ""})
            assertFalse(ShaderEarlyCompositeCrop.isEligible(name));
        assertFalse(ShaderEarlyCompositeCrop.isEligible(null));
    }

    @Test public void preservesNativePixelsAndGenerousInputHalo() {
        int[] box = ShaderEarlyCompositeCrop.rectangle("composite",
                800, 430, 160, 90, 1920, 1080, 0, 0, 1920, 1080);
        assertArrayEquals(new int[]{416, 46, 928, 858}, box);
    }

    @Test public void refusesOffsetScaledInvalidOrOversizedTarget() {
        assertNull(ShaderEarlyCompositeCrop.rectangle("composite", 500, 200, 80, 80,
                1920, 1080, 0, 0, 960, 540));
        assertNull(ShaderEarlyCompositeCrop.rectangle("composite", 500, 200, 80, 80,
                1920, 1080, 1, 0, 1920, 1080));
        assertNull(ShaderEarlyCompositeCrop.rectangle("composite", 1900, 200, 80, 80,
                1920, 1080, 0, 0, 1920, 1080));
        assertNull(ShaderEarlyCompositeCrop.rectangle("composite", 0, 0, 1920, 1080,
                1920, 1080, 0, 0, 1920, 1080));
        assertNull(ShaderEarlyCompositeCrop.rectangle("final", 800, 430, 160, 90,
                1920, 1080, 0, 0, 1920, 1080));
    }
}
