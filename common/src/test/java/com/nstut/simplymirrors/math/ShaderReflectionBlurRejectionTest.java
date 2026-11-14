package com.nstut.simplymirrors.math;
import org.junit.Test;
import static org.junit.Assert.*;
public class ShaderReflectionBlurRejectionTest {
    @Test public void preservesDerivativesBeforeDivergentRejectionAndMainCameraPath() {
        String original="vec4 sampleBlurFilteredReflection(vec4 centerCol,vec3 nViewPos,float dither,float z0){vec2 sampleCoord=vec2(0);vec4 sampleCol=texture2D(colortex7,sampleCoord);for(int i=0;i<25;i++){vec4 texture1Sample=vec4(0);if(texture1Sample.x>0.1)continue;float weight=1;sum+=sampleCol*weight;}return sum;}";
        String patched=ShaderReflectionBlurRejection.transformFragment(original);
        String optimized=patched.substring(patched.indexOf("vec4 sampleBlurFilteredReflection"));
        assertTrue(optimized.indexOf("dFdx(sampleCoord)")<optimized.indexOf("continue;"));
        assertTrue(optimized.indexOf("textureGrad(colortex7")>optimized.indexOf("continue;"));
        assertTrue(patched.contains(original.replace("sampleBlurFilteredReflection","simplymirrors_MainReflectionBlur")));
        assertTrue(optimized.contains("if (!simplymirrors_ReflectionBlurRejection) return simplymirrors_MainReflectionBlur"));
        assertSame(patched,ShaderReflectionBlurRejection.transformFragment(patched));
    }
}
