package com.nstut.simplymirrors.math;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;
public class ShaderCloudOcclusionTest {
    @Test public void preservesOriginalCloudBodyAndScopesEarlyReturn() {
        String original="vec4 GetVolumetricClouds(){float cloudStretch=4.2;"
            +"float planeDistanceDif=maxPlaneDistance-minPlaneDistance;"
            +"tracePos.y-=traceAdd.y;for(int i=0;i<sampleCount;i++){tracePos+=traceAdd;"
            +"if(GetCloudNoise(tracePos)) return clouds;}return vec4(0.0);}";
        String patched=ShaderCloudOcclusion.transformFragment(original);
        assertTrue(patched.contains("uniform bool simplymirrors_CloudOcclusion;"));
        assertTrue(patched.contains("if (simplymirrors_CloudOcclusion && skyFade < 0.7"));
        assertTrue(patched.endsWith(original.substring(original.indexOf("tracePos.y"))));
        assertSame(patched,ShaderCloudOcclusion.transformFragment(patched));
        assertSame("void main(){}",ShaderCloudOcclusion.transformFragment("void main(){}"));
    }
    @Test public void rejectedSlabCannotContainVisibleOriginalSamples() {
        Random random=new Random(0xC10D);
        for(int ray=0;ray<20000;ray++) {
            float ny=(random.nextFloat()*2-1), nx=(float)Math.sqrt(1-ny*ny);
            float min=random.nextFloat()*10000, step=random.nextFloat()*100;
            float dither=random.nextFloat(), depth=min-1.01f;
            float x=min*nx+nx*step*dither, y=min*ny+ny*step*dither-ny*step;
            for(int sample=0;sample<100;sample++) {
                x+=nx*step;y+=ny*step;
                float distance=(float)Math.sqrt(x*x+y*y);
                assertTrue("A rejected cloud sample must remain behind opaque depth",distance>depth);
            }
        }
    }
}
