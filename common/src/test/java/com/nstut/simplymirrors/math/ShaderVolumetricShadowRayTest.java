package com.nstut.simplymirrors.math;
import org.junit.Test;
import static org.junit.Assert.*;
public class ShaderVolumetricShadowRayTest {
    @Test public void movesRotationBeforeLoopAndRetainsOriginalDistortion() {
        String source="vec3 GetShadowPos(vec3 playerPos){vec3 shadowPos=PlayerToShadow(playerPos);"
            +"float bias=shadowMapBias;shadowPos.z*=0.2f;return shadowPos*0.5f+0.5f;}"
            +"vec4 GetVolumetricLight(){vec3 rayDirection=nViewPosInSceneSpace;"
            +"for(int i=0;i<10;i++){scenePos=rayDirection*nextDistance;vec3 shadowPos=GetShadowPos(scenePos);}}";
        String patched=ShaderVolumetricShadowRay.transformFragment(source);
        assertTrue(patched.indexOf("mat3(shadowModelView) * rayDirection")<patched.indexOf("for(int"));
        assertTrue(patched.contains("simplymirrors_ShadowRay * nextDistance + shadowModelView[3].xyz"));
        assertTrue(patched.contains("shadowPos.z*=0.2f;return shadowPos*0.5f+0.5f;"));
        assertEquals(patched,ShaderVolumetricShadowRay.transformFragment(patched));
    }
}
