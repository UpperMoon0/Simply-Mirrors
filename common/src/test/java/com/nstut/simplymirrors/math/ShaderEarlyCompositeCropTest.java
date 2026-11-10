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

    @Test public void nativeViewportPreservesPixelsAndGenerousInputHalo() {
        int[] box = ShaderEarlyCompositeCrop.rectangle("composite",
                800, 430, 160, 90, 1920, 1080, 0, 0, 1920, 1080);
        assertArrayEquals(new int[]{416, 46, 928, 858}, box);
    }

    @Test public void existingHalfResolutionSsrViewportMapsNativeMirrorCoverage() {
        int[] box = ShaderEarlyCompositeCrop.rectangle("composite",
                800, 430, 160, 90, 1920, 1080, 0, 0, 960, 540);
        assertArrayEquals(new int[]{208, 23, 464, 429}, box);
        // Windows' 1920x1080 client content may be 1920x1061. Iris floors
        // its 0.5-scaled intermediate viewport to 960x530, not 960x531.
        assertArrayEquals(new int[]{177, 0, 602, 530},
                ShaderEarlyCompositeCrop.rectangle("composite",
                        739, 305, 434, 434, 1920, 1061, 0, 0, 960, 530));
    }

    @Test public void refusesUnsupportedScaleOffsetMalformedOrOversizedTarget() {
        assertNull(ShaderEarlyCompositeCrop.rectangle("composite", 500, 200, 80, 80,
                1920, 1080, 0, 0, 960, 539));
        assertNull(ShaderEarlyCompositeCrop.rectangle("composite", 500, 200, 80, 80,
                1920, 1080, 0, 0, 1440, 810));
        assertNull(ShaderEarlyCompositeCrop.rectangle("composite", 500, 200, 80, 80,
                1920, 1080, 1, 0, 960, 540));
        assertNull(ShaderEarlyCompositeCrop.rectangle("composite", 1900, 200, 80, 80,
                1920, 1080, 0, 0, 1920, 1080));
        assertNull(ShaderEarlyCompositeCrop.rectangle("composite", 0, 0, 1920, 1080,
                1920, 1080, 0, 0, 960, 540));
        assertNull(ShaderEarlyCompositeCrop.rectangle("final", 800, 430, 160, 90,
                1920, 1080, 0, 0, 960, 540));
        assertNull(ShaderEarlyCompositeCrop.rectangle("composite", 800, 430, 160, 90,
                40000, 1080, 0, 0, 20000, 540));
    }
}
