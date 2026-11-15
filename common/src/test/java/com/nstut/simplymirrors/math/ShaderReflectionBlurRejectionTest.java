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
    @Test public void expandsAllTapsInOrderAndKeepsContinueLocal() {
        String original="vec4 sampleBlurFilteredReflection(vec4 centerCol,vec3 nViewPos,float dither,float z0){ int k = 2; for (int dy = -k; dy <= k; dy++) { for (int dx = -k; dx <= k; dx++) { vec2 sampleCoord=vec2(dx,dy); vec4 sampleCol=texture2D(colortex7,sampleCoord); vec4 texture1Sample=vec4(0); if(texture1Sample.x>0.1)continue; float weight=exp(-float(dx*dx+dy*dy)/12.5);sum+=sampleCol*weight; }}return sum;}";
        String patched=ShaderReflectionBlurRejection.transformFragment(original);
        String optimized=patched.substring(patched.indexOf("vec4 sampleBlurFilteredReflection"));
        assertEquals(25,optimized.split("while\\(false\\)",-1).length-1);
        assertEquals(25,optimized.split("textureLod\\(colortex7",-1).length-1);
        assertTrue(optimized.indexOf("const int dy=-2; const int dx=-2")<optimized.indexOf("const int dy=2; const int dx=2"));
        assertFalse(optimized.contains("dy++"));
        assertTrue(patched.contains(original.replace("sampleBlurFilteredReflection","simplymirrors_MainReflectionBlur")));
    }

}
