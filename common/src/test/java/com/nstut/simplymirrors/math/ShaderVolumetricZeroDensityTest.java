package com.nstut.simplymirrors.math;
import org.junit.Test;
import static org.junit.Assert.*;
public class ShaderVolumetricZeroDensityTest {
    static final String SHADER = "#version 430 core\n"
      +"vec4 GetVolumetricLight(inout float vlFactor, float lViewPos0) {"
      +"vec4 volumetricLight=vec4(0.0); float lastDistance=0.0;"
      +"for(int i=0;i<=totalSamples;i++){"
      +"vec3 localDensity=vec3(0.0); float nextDistance=1.0;"
      +"float currentWeight=smoothstep1(pow(lastDistance/maxDistance,fogCurve));"
      +"float nextWeight=smoothstep1(pow(nextDistance/maxDistance,fogCurve));"
      +"float sliceWeight=nextWeight-currentWeight;"
      +"vec4 stepResult=vec4(localDensity*sliceWeight,sliceWeight);"
      +"if(nextDistance>lViewPos0){if(nextDistance>entityLinearDepth)break;stepResult.rgb*=translucentMult;}"
      +"volumetricLight+=stepResult;lastDistance=nextDistance;}"
      +"vlFactor+=1.0;volumetricLight.rgb*=vlColor;"
      +"volumetricLight=max(volumetricLight,vec4(0.0));return volumetricLight;}"
      +"void main(){vec4 volumetricEffect=vec4(0.0);"
      +"volumetricEffect=GetVolumetricLight(vlFactor,lViewPos0);color+=volumetricEffect.rgb;}";
    @Test public void rewritesOnlyGuardedZeroRgbStep() {
        String optimized=ShaderVolumetricZeroDensity.transformFragment(SHADER);
        assertNotEquals(SHADER,optimized);
        assertTrue(optimized.contains("nextDistance <= lViewPos0"));
        assertTrue(optimized.contains("lastDistance = nextDistance;\n    continue;"));
        assertTrue(optimized.contains("vlFactor+=1.0;"));
        assertTrue(optimized.contains("if(nextDistance>entityLinearDepth)break;"));
        assertEquals(optimized,ShaderVolumetricZeroDensity.transformFragment(optimized));
        String scoped=ShaderVolumetricReflectionScope.scope(SHADER,optimized);
        assertTrue(scoped.contains("simplymirrors_MainVolumetricLight"));
        assertTrue(scoped.contains("if (!simplymirrors_VolumetricReflection)"));
    }
    @Test public void rejectsAlphaOrWholeVectorConsumers() {
        for(String read:new String[]{"volumetricEffect.a","volumetricEffect","volumetricEffect[3]","volumetricEffect.rgba"}) {
            String unsafe=SHADER.replace("color+=volumetricEffect.rgb;","consume("+read+");");
            assertEquals(unsafe,ShaderVolumetricZeroDensity.transformFragment(unsafe));
        }
    }
    @Test public void rejectsAlteredIntegratorOrAdditionalCaller() {
        for(String unsafe:new String[]{
            SHADER.replace("vlFactor+=1.0;","vlFactor+=volumetricLight.a;"),
            SHADER.replace("lastDistance=nextDistance;}","lastDistance=nextDistance;sideEffect();}"),
            SHADER.replace("stepResult.rgb*=translucentMult;","stepResult.rgb*=DrawEnderBeams();"),
            SHADER.replace("color+=volumetricEffect.rgb;","color+=volumetricEffect.rgb;GetVolumetricLight(vlFactor,lViewPos0);")
        }) assertEquals(unsafe,ShaderVolumetricZeroDensity.transformFragment(unsafe));
    }
    @Test public void preservesWeightsAcrossZeroDensitySteps() {
        double last=0, rgb=0, optimized=0;
        double[] distances={0.1,0.2,0.35,0.5,0.8,1.0};
        double[] density={0,1,0,0,0.25,1};
        for(int i=0;i<distances.length;i++){
            double a=Math.pow(last,0.7), b=Math.pow(distances[i],0.7);
            double weight=b*b*(3-2*b)-a*a*(3-2*a);
            rgb+=density[i]*weight;
            if(density[i]!=0)optimized+=density[i]*weight;
            last=distances[i];
        }
        assertEquals(rgb,optimized,0.0);
    }
}
