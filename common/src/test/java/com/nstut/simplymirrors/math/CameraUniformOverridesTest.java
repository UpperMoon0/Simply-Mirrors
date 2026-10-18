package com.nstut.simplymirrors.math;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class CameraUniformOverridesTest {
    private static final float[] IDENTITY = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    private static final float[] REFLECT_X = {-1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    private static final float[] REFLECT_Y = {1,0,0,0, 0,-1,0,0, 0,0,1,0, 0,0,0,1};
    private static final class Uniforms implements CameraUniformOverrides.Uniforms {
        int program = 1;
        final Map<Integer, Map<String, float[]>> floats = new HashMap<Integer, Map<String, float[]>>();
        final Map<Integer, int[]> ints = new HashMap<Integer, int[]>();
        Uniforms() { add(1); add(2); }
        void add(int id) {
            Map<String, float[]> values = new HashMap<String, float[]>();
            values.put("gbufferModelView", IDENTITY.clone());
            values.put("gbufferModelViewInverse", IDENTITY.clone());
            values.put("gbufferProjection", IDENTITY.clone());
            values.put("gbufferProjectionInverse", IDENTITY.clone());
            values.put("gbufferPreviousModelView", REFLECT_Y.clone());
            values.put("shadowModelView", IDENTITY.clone());
            values.put("shadowModelViewInverse", IDENTITY.clone());
            values.put("cameraPosition", new float[] {12.5f, 64.25f, -2.25f});
            values.put("cameraPositionFract", new float[] {.5f,.25f,.75f});
            values.put("eyeAltitude", new float[] {64.25f});
            values.put("previousCameraPosition", new float[] {11,64,-3});
            values.put("sunPosition", new float[] {10,20,30});
            floats.put(id, values);
            ints.put(id, new int[] {30012,64,-3});
        }
        public int currentProgram() { return program; }
        public void bind(int id) { program=id; }
        public float[] readFloats(int id, String name, int size) {
            float[] value=floats.get(id).get(name); return value==null?null:value.clone();
        }
        public int[] readInts(int id, String name, int size) {return ints.containsKey(id)?ints.get(id).clone():null;}
        public void writeFloats(int id, String name, float[] value) {floats.get(id).put(name,value.clone());}
        public void writeInts(int id, String name, int[] value) {ints.put(id,value.clone());}
        float[] get(String name) {return floats.get(program).get(name);}
    }
    private static CameraUniformOverrides pass(Uniforms gl, float[] view, double[] eye) {
        return new CameraUniformOverrides(gl,IDENTITY,IDENTITY,view,new double[] {30012.5,64.25,-2.25},eye);
    }
    @Test public void cameraChangesWithinOneFrameAndRestoresWithoutInvalidatingTemporalValues() {
        Uniforms gl=new Uniforms();
        CameraUniformOverrides pass=pass(gl,REFLECT_X,new double[] {30008.5,64.25,-2.25});
        // Iris's PER_FRAME cache has already uploaded the main camera and now skips updates.
        pass.upload();
        assertArrayEquals(REFLECT_X,gl.get("gbufferModelView"),0);
        assertArrayEquals(REFLECT_X,gl.get("gbufferModelViewInverse"),0);
        assertArrayEquals(new float[] {8.5f,64.25f,-2.25f},gl.get("cameraPosition"),0);
        assertArrayEquals(new float[] {-10,20,30},gl.get("sunPosition"),0);
        assertArrayEquals(REFLECT_Y,gl.get("gbufferPreviousModelView"),0);
        assertArrayEquals(new float[] {11,64,-3},gl.get("previousCameraPosition"),0);
        pass.upload(); // repeated draws must not accumulate the eye delta
        assertEquals(8.5f,gl.get("cameraPosition")[0],0);
        gl.bind(2);pass.upload();gl.bind(99);
        pass.restore();
        assertEquals(99,gl.program);
        for(int id:new int[]{1,2}) {
            gl.bind(id);
            assertArrayEquals(IDENTITY,gl.get("gbufferModelView"),0);
            assertArrayEquals(new float[] {12.5f,64.25f,-2.25f},gl.get("cameraPosition"),0);
            assertArrayEquals(new int[] {30012,64,-3},gl.ints.get(id));
        }
    }
    @Test public void consecutiveMirrorPlanesPreserveOriginShiftAndNegativeFractions() {
        Uniforms gl=new Uniforms();
        CameraUniformOverrides first=pass(gl,REFLECT_X,new double[] {30008.5,64.25,-2.25});
        first.upload();first.restore();
        CameraUniformOverrides second=pass(gl,REFLECT_Y,new double[] {30012.5,63.75,-3.125});
        second.upload();
        assertArrayEquals(new float[] {12.5f,63.75f,-3.125f},gl.get("cameraPosition"),0);
        assertArrayEquals(new float[] {.5f,.75f,.875f},gl.get("cameraPositionFract"),0);
        assertArrayEquals(new int[] {30012,63,-4},gl.ints.get(1));
        assertEquals(63.75f,gl.get("eyeAltitude")[0],0);
        second.restore();
        assertArrayEquals(IDENTITY,gl.get("gbufferModelViewInverse"),0);
        assertArrayEquals(new float[] {12.5f,64.25f,-2.25f},gl.get("cameraPosition"),0);
    }
    @Test public void shadowUniformsFollowActualLightCameraAndRestoreMainLightCamera() {
        Uniforms gl=new Uniforms();
        CameraUniformOverrides pass=pass(gl,REFLECT_X,new double[] {30008.5,64.25,-2.25});
        float[] shadow=IDENTITY.clone();shadow[12]=.25f;
        pass.shadows(shadow,IDENTITY);pass.upload();
        assertEquals(.25f,gl.get("shadowModelView")[12],0);
        assertEquals(-.25f,gl.get("shadowModelViewInverse")[12],0);
        shadow[12]=.75f;pass.shadows(shadow,IDENTITY);pass.upload();
        assertEquals(.75f,gl.get("shadowModelView")[12],0);
        pass.restore();
        assertArrayEquals(IDENTITY,gl.get("shadowModelView"),0);
        assertArrayEquals(IDENTITY,gl.get("shadowModelViewInverse"),0);
    }
    @Test public void inactiveOrOptimizedOutUniformsAreSafe() {
        Uniforms gl=new Uniforms(); gl.floats.get(1).clear();gl.ints.clear();
        CameraUniformOverrides pass=pass(gl,REFLECT_X,new double[] {30008.5,64.25,-2.25});
        gl.bind(0);pass.upload();gl.bind(1);pass.upload();pass.restore();
        assertTrue(gl.floats.get(1).isEmpty()); assertTrue(gl.ints.isEmpty());
    }
    @Test public void restorationRunsAfterAnInterruptedMirror() {
        Uniforms gl=new Uniforms();
        CameraUniformOverrides pass=pass(gl,REFLECT_X,new double[] {30008.5,64.25,-2.25});
        try { pass.upload();throw new IllegalStateException("interrupted render"); }
        catch(IllegalStateException expected) {assertEquals("interrupted render",expected.getMessage());}
        finally {pass.restore();}
        assertArrayEquals(IDENTITY,gl.get("gbufferModelView"),0);
        assertEquals(12.5f,gl.get("cameraPosition")[0],0);
    }
}
