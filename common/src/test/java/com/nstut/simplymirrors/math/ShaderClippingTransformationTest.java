package com.nstut.simplymirrors.math;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class ShaderClippingTransformationTest {
    private enum Stage {VERTEX,GEOMETRY,TESS_EVAL,FRAGMENT}
    private static final String SOURCE="#version 330 core\nvoid main(){gl_Position=vec4(1);if(true)return;}\n";
    @Test public void clipsAfterEarlyReturnAndIsIdempotent(){
        String patched=ShaderClippingTransformation.transform(SOURCE,ShaderClippingTransformation.Stage.VERTEX,"simplymirrors_InverseProjection");
        assertTrue(patched.contains("simplymirrors_unclippedMain();"));
        assertTrue(patched.contains("gl_ClipDistance[0] ="));
        assertEquals(patched,ShaderClippingTransformation.transform(patched,ShaderClippingTransformation.Stage.VERTEX,"simplymirrors_InverseProjection"));
    }
    @Test public void cacheCopyLeavesShadowAndCompositeUnmodified(){
        Map<Stage,String> original=new EnumMap<>(Stage.class);original.put(Stage.VERTEX,SOURCE);
        Map<Stage,String> world=ShaderClippingTransformation.transformProgram("SODIUM","gbuffers_terrain",original);
        assertNotSame(original,world);assertEquals(SOURCE,original.get(Stage.VERTEX));
        assertSame(original,ShaderClippingTransformation.transformProgram("SODIUM","shadow",original));
        assertSame(original,ShaderClippingTransformation.transformProgram("COMPOSITE","composite",original));
        assertSame(original,ShaderClippingTransformation.transformProgram("VANILLA","sky_basic",original));
    }
    @Test public void clipsFinalGeometryStageBeforeEveryEmission(){
        Map<Stage,String> original=new EnumMap<>(Stage.class);original.put(Stage.VERTEX,SOURCE);
        original.put(Stage.GEOMETRY,"#version 330 core\nvoid main(){if(true)EmitVertex();else EmitStreamVertex(0);}");
        Map<Stage,String> result=ShaderClippingTransformation.transformProgram("SODIUM","gbuffers_water",original);
        assertEquals(SOURCE,result.get(Stage.VERTEX));
        assertTrue(result.get(Stage.GEOMETRY).contains("(gl_Position); EmitVertex(); }else {"));
        assertTrue(result.get(Stage.GEOMETRY).contains("EmitStreamVertex(0); }"));
    }
    @Test public void explicitPerVertexOutputGetsClipDistance(){
        String result=ShaderClippingTransformation.transform("#version 330 core\nout gl_PerVertex {vec4 gl_Position;};\nvoid main(){}",ShaderClippingTransformation.Stage.VERTEX,"simplymirrors_InverseProjection");
        assertTrue(result.contains("float gl_ClipDistance[1];"));
    }
    @Test public void allSixPlanesClipWallAndKeepReflectedRoom(){
        for(double[] n:new double[][]{{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}}){
            float[] view=ReflectionTransform.matrix(n[0],n[1],n[2]);
            float[] plane=MirrorMatrices.clipPlane(view,n[0],n[1],n[2],0,0,0);
            double[] room=MirrorMatrices.transform(view,n[0],n[1],n[2],1);
            double[] wall=MirrorMatrices.transform(view,-n[0],-n[1],-n[2],1);
            double a=0,b=0;for(int i=0;i<4;i++){a+=room[i]*plane[i];b+=wall[i]*plane[i];}
            assertTrue(a>0);assertTrue(b<0);
        }
    }
}
