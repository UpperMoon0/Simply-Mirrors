package com.nstut.simplymirrors.math;
import org.junit.Test;
import static org.junit.Assert.*;
public class ShaderVolumetricWeightReuseTest {
    private static final String LOOP = "vec4 GetVolumetricLight(){float lastDistance=0.0f; for(int i=0;i<=totalSamples;i++){"
        +"float currentWeight=smoothstep1(pow(lastDistance/maxDistance,fogCurve));"
        +"float nextWeight=smoothstep1(pow(nextDistance/maxDistance,fogCurve));"
        +"float sliceWeight=nextWeight-currentWeight;volumetricLight+=stepResult;lastDistance=nextDistance;}}";
    @Test public void carriesIdenticalWeightWithoutChangingSamplingOrAccumulation() {
        String patched=ShaderVolumetricWeightReuse.transformFragment(LOOP);
        assertFalse(patched.equals(LOOP));
        assertTrue(patched.contains("simplymirrors_PreviousFogWeight = nextWeight;"));
        assertTrue(patched.contains("pow(nextDistance/maxDistance,fogCurve)"));
        assertFalse(patched.contains("pow(lastDistance/maxDistance,fogCurve)"));
        assertTrue(patched.contains("volumetricLight+=stepResult;"));
        assertEquals(patched,ShaderVolumetricWeightReuse.transformFragment(patched));
    }
    @Test public void leavesDifferentIntegratorsUnchanged() {
        unchanged(LOOP.replace("nextDistance/maxDistance","otherDistance/maxDistance"));
    }
    private static String unchanged(String s) { assertSame(s,ShaderVolumetricWeightReuse.transformFragment(s)); return s; }
    @Test public void carryMatchesOriginalAcrossPositivePinnedFogCurves() {
        for(double exponent:new double[]{0.5,0.7,0.9}) {
            double previousDistance=0, carry=0;
            for(int i=0;i<31;i++) {
                double distance=128*Math.pow((i+0.37)/31,1.5);
                double original=smooth(Math.pow(previousDistance/128,exponent));
                assertEquals(original,carry,0.0);
                carry=smooth(Math.pow(distance/128,exponent));
                previousDistance=distance;
            }
        }
    }
    private static double smooth(double x) { return x*x*(3-2*x); }
}
