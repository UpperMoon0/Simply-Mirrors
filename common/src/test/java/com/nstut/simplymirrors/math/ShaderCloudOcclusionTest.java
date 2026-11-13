package com.nstut.simplymirrors.math;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;
public class ShaderCloudOcclusionTest {
    private String fixture(String suffix) {
        return "vec4 GetVolumetricClouds(){float minPlaneDistance=1.0;"
            +"tracePos.y-=traceAdd.y;for(int i=0;i<sampleCount;i++){tracePos+=traceAdd;"
            +"float lTracePos=length(tracePos-cameraPos);if(lTracePos>lViewPosM){"
            +"if(skyFade<0.7"+suffix+")continue;else cloudMult=skyMult0;}"
            +"if(GetCloudNoise(tracePos))return clouds;}return vec4(0.0);}";
    }
    @Test public void exitsOnlyHiddenReflectionSamples() {
        for(String suffix:new String[]{"","f"}) {
            String original=fixture(suffix),patched=ShaderCloudOcclusion.transformFragment(original);
            assertTrue(patched.contains("uniform bool simplymirrors_CloudOcclusion;"));
            assertTrue(patched.contains("if (simplymirrors_CloudOcclusion) break; else continue;"));
            assertTrue(patched.endsWith("else cloudMult=skyMult0;}if(GetCloudNoise(tracePos))return clouds;}return vec4(0.0);}"));
            assertSame(patched,ShaderCloudOcclusion.transformFragment(patched));
        }
        assertSame("void main(){}",ShaderCloudOcclusion.transformFragment("void main(){}"));
    }
    @Test public void monotonicRaysCannotRevealLaterSamples() {
        Random random=new Random(0xC10D);
        for(int ray=0;ray<20000;ray++) {
            float ny=random.nextFloat()*2-1, nx=(float)Math.sqrt(1-ny*ny);
            float min=random.nextFloat()*10000, step=random.nextFloat()*100;
            float dither=random.nextFloat(), depth=random.nextFloat()*12000;
            float x=min*nx+nx*step*dither, y=min*ny+ny*step*dither-ny*step;
            boolean hidden=false;
            for(int sample=0;sample<100;sample++) {
                x+=nx*step;y+=ny*step;
                float distance=(float)Math.sqrt(x*x+y*y);
                if(hidden)assertTrue("A hidden ray cannot reveal a later cloud",distance>depth);
                hidden=hidden || distance>depth;
            }
        }
    }
}
