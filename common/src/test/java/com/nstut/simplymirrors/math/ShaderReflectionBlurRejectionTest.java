package com.nstut.simplymirrors.math;
import org.junit.Test;
import static org.junit.Assert.*;
public class ShaderReflectionBlurRejectionTest {
    @Test public void defersBaseLevelFetchAndPreservesMainCameraPath() {
        String original="vec4 sampleBlurFilteredReflection(vec4 centerCol,vec3 nViewPos,float dither,float z0){vec2 sampleCoord=vec2(0);vec4 sampleCol=texture2D(colortex7,sampleCoord);for(int i=0;i<25;i++){vec4 texture1Sample=vec4(0);if(texture1Sample.x>0.1)continue;float weight=1;sum+=sampleCol*weight;}return sum;}";
        String patched=ShaderReflectionBlurRejection.transformFragment(original);
        String optimized=patched.substring(patched.indexOf("vec4 sampleBlurFilteredReflection"));
        assertFalse(optimized.contains("dFdx(sampleCoord)"));
        assertTrue(optimized.indexOf("textureLod(colortex7")>optimized.indexOf("continue;"));
        assertTrue(patched.contains(original.replace("sampleBlurFilteredReflection","simplymirrors_MainReflectionBlur")));
        assertTrue(optimized.contains("if (!simplymirrors_ReflectionBlurRejection) return simplymirrors_MainReflectionBlur"));
        assertSame(patched,ShaderReflectionBlurRejection.transformFragment(patched));
    }
    @Test public void foldsGaussianWeightsWithoutExpandingTheKernel() {
        String original="vec4 sampleBlurFilteredReflection(vec4 centerCol,vec3 nViewPos,float dither,float z0){ const float spatialFactorM = 2.0 * spatialFactor * spatialFactor; int k = 2; for (int dy = -k; dy <= k; dy++) { for (int dx = -k; dx <= k; dx++) { vec2 sampleCoord=vec2(dx,dy); vec4 sampleCol=texture2D(colortex7,sampleCoord); vec4 texture1Sample=vec4(0); if(texture1Sample.x>0.1)continue; float spatialDist2=float(dx*dx+dy*dy); float weight = exp(-spatialDist2 / spatialFactorM); sum+=sampleCol*weight; }}return sum;}";
        String patched=ShaderReflectionBlurRejection.transformFragment(original);
        String optimized=patched.substring(patched.indexOf("vec4 sampleBlurFilteredReflection"));
        assertTrue(optimized.contains("const float simplymirrors_BlurWeights[9]"));
        assertTrue(optimized.contains("exp(-8.0 / spatialFactorM)"));
        assertTrue(optimized.contains("float weight = simplymirrors_BlurWeights[int(spatialDist2)];"));
        assertFalse(optimized.contains("exp(-spatialDist2"));
        assertTrue(optimized.contains("dy++"));
        assertTrue(optimized.contains("dx++"));
        assertTrue(patched.contains(original.replace("sampleBlurFilteredReflection","simplymirrors_MainReflectionBlur")));
        for(int dy=-2;dy<=2;dy++) for(int dx=-2;dx<=2;dx++) {
            int radius=dx*dx+dy*dy;
            assertTrue(radius>=0 && radius<=8);
        }
    }
}
