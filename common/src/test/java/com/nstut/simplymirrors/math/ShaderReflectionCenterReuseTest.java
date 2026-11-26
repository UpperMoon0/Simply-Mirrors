package com.nstut.simplymirrors.math;
import org.junit.Test;
import static org.junit.Assert.*;

public class ShaderReflectionCenterReuseTest {
    private static final String HELPER =
        "vec4 sampleBlurFilteredReflection(vec4 centerCol, vec3 nViewPos, float dither, float z0) {\n"+
        "vec4 texture4 = texture2D(colortex4, texCoord);\n"+
        "int k = 2; for (int dy = -k; dy <= k; dy++) { for (int dx = -k; dx <= k; dx++) {\n"+
        "vec2 offset = vec2(float(dx), float(dy)) * texelSize; vec2 sampleCoord = texCoord + offset;\n"+
        "vec4 sampleCol = texture2D(colortex7, sampleCoord); if (texture4.x > 0.1) continue; sum += sampleCol * weight; }} return sum; }\n";
    private static final String CALLER =
        "void main() { vec4 compositeReflection = texture2D(colortex7, texCoord);\n"+
        "if (neighbor.a > compositeReflection.a) compositeReflection = neighbor;\n"+
        "float fresnelM = pow2(texture2D(colortex4, texCoord).a);\n"+
        "compositeReflection = sampleBlurFilteredReflection(compositeReflection, nViewPos, dither, z0); }\n";
    @Test public void originalAndNeighborPathPreserved() {
        String src=HELPER+CALLER, out=ShaderReflectionCenterReuse.transformFragment(src);
        assertNotEquals(src,out);
        assertTrue(out.contains(HELPER));
        assertTrue(out.contains("if (neighbor.a > compositeReflection.a) compositeReflection = neighbor;"));
        assertTrue(out.contains("simplymirrors_CenterBlur(simplymirrors_RawCenter, nViewPos, dither, z0, simplymirrors_CenterNormal)"));
        assertTrue(out.contains("? centerCol : texture2D(colortex7, sampleCoord);"));
        assertTrue(out.contains("vec4 texture4 = simplymirrors_CenterNormal;"));
        assertTrue(out.contains(": sampleBlurFilteredReflection(compositeReflection, nViewPos, dither, z0)"));
        assertSame(out,ShaderReflectionCenterReuse.transformFragment(out));
        assertNotEquals(src.replace("texture2D","texture"),ShaderReflectionCenterReuse.transformFragment(src.replace("texture2D","texture")));
    }
    @Test public void unknownShapesFailOpen() {
        for(String src:new String[]{
            HELPER.replace("return sum","return centerCol")+CALLER,
            HELPER.replace("int k = 2","int k = 3")+CALLER,
            HELPER+CALLER.replace("colortex4, texCoord","colortex4, otherCoord"),
            HELPER+CALLER.replace("colortex7, texCoord","colortex7, otherCoord"),
            HELPER+CALLER+CALLER, HELPER+CALLER+"// simplymirrors_ReflectionBlurRejection"
        }) assertSame(src,ShaderReflectionCenterReuse.transformFragment(src));
    }
}
