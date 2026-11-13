package com.nstut.simplymirrors.math;
import org.junit.Test;
import static org.junit.Assert.*;
public class ShaderVolumetricReflectionScopeTest {
    @Test public void mainCameraRetainsOriginalFunctionIncludingInoutParameter() {
        String original="#version 410 core\nvec4 GetVolumetricLight(inout float factor, vec3 position){if(factor>0.0){factor+=1.0;}return vec4(position,factor);}";
        String optimized=original.replace("factor+=1.0","factor+=2.0");
        String scoped=ShaderVolumetricReflectionScope.scope(original,optimized);
        assertTrue(scoped.contains("simplymirrors_MainVolumetricLight(inout float factor, vec3 position){if(factor>0.0){factor+=1.0;}"));
        assertTrue(scoped.contains("if (!simplymirrors_VolumetricReflection) return simplymirrors_MainVolumetricLight(factor,position);"));
        assertTrue(scoped.contains("factor+=2.0"));
        assertTrue(scoped.startsWith("#version 410 core"));
        assertEquals(original,ShaderVolumetricReflectionScope.scope(original,original));
    }
}
