package com.nstut.simplymirrors.math;

import java.util.LinkedHashMap;
import java.util.Map;

/** A camera-scoped GPU overlay. Never invalidates Iris's frame or temporal supplier caches. */
public final class CameraUniformOverrides {
    public interface Uniforms {
        int currentProgram();
        void bind(int program);
        float[] readFloats(int program, String name, int size);
        int[] readInts(int program, String name, int size);
        void writeFloats(int program, String name, float[] value);
        void writeInts(int program, String name, int[] value);
    }
    private static final String[] MATRICES = {"gbufferModelView", "gbufferModelViewInverse", "gbufferProjection", "gbufferProjectionInverse"};
    private static final String[] PREVIOUS = {"gbufferPreviousModelView", "gbufferPreviousModelViewInverse", "gbufferPreviousProjection", "gbufferPreviousProjectionInverse"};
    private static final String[] SHADOWS = {"shadowModelView", "shadowModelViewInverse", "shadowProjection", "shadowProjectionInverse"};
    private static final String[] DIRECTIONS = {"sunPosition", "moonPosition", "shadowLightPosition", "upPosition"};
    private final Uniforms uniforms;
    private final float[][] matrices;
    private final float[] viewChange;
    private final double[] eye, delta;
    private float[][] shadowMatrices;
    private final Map<Integer, Program> programs = new LinkedHashMap<Integer, Program>();

    private final float[][] previousMatrices;
    private final double[] previousEye, previousDelta;
    private final boolean previousValid;
    public CameraUniformOverrides(Uniforms uniforms, float[] projection, float[] mainView,
                                  float[] reflectedView, double[] mainEye, double[] reflectedEye) {
        this(uniforms, projection,mainView,reflectedView,mainEye,reflectedEye,
                reflectedView,projection,mainEye,reflectedEye,false);
    }
    public CameraUniformOverrides(Uniforms uniforms, float[] projection, float[] mainView,
                                  float[] reflectedView, double[] mainEye, double[] reflectedEye,
                                  float[] previousReflectedView, float[] previousReflectedProjection,
                                  double[] previousMainEye,double[] previousReflectedEye,
                                  boolean previousValid) {
        this.uniforms=uniforms;
        matrices=new float[][] {reflectedView.clone(),MirrorMatrices.inverse(reflectedView),
                projection.clone(),MirrorMatrices.inverse(projection)};
        previousMatrices=new float[][] {previousReflectedView.clone(),MirrorMatrices.inverse(previousReflectedView),
                previousReflectedProjection.clone(),MirrorMatrices.inverse(previousReflectedProjection)};
        viewChange=MirrorMatrices.multiply(reflectedView,MirrorMatrices.inverse(mainView));
        eye=reflectedEye.clone();
        previousEye=previousReflectedEye.clone();
        previousDelta=new double[] {previousEye[0]-previousMainEye[0],
                previousEye[1]-previousMainEye[1],previousEye[2]-previousMainEye[2]};
        this.previousValid=previousValid;
        delta=new double[] {eye[0]-mainEye[0],eye[1]-mainEye[1],eye[2]-mainEye[2]};
    }
    public void shadows(float[] view, float[] projection) {
        shadowMatrices = view==null || projection==null ? null : new float[][] {view,MirrorMatrices.inverse(view),projection,MirrorMatrices.inverse(projection)};
    }
    public void upload() {
        int id = uniforms.currentProgram();
        if (id == 0) return;
        Program program = programs.get(id);
        if (program == null) {
            program = new Program(id);
            programs.put(id, program);
        }
        program.upload(false);
    }
    /** Restore GPU values too: Iris may skip the next upload when its cached value is unchanged. */
    public void restore() {
        int bound = uniforms.currentProgram();
        try {
            for (Map.Entry<Integer, Program> entry : programs.entrySet()) {
                uniforms.bind(entry.getKey());
                entry.getValue().upload(true);
            }
        } finally {
            uniforms.bind(bound);
            programs.clear();
        }
    }
    private final class Program {
        final int id;
        final Map<String, float[]> original = new LinkedHashMap<String, float[]>();
        final Map<String, float[]> reflected = new LinkedHashMap<String, float[]>();
        int[] originalInt, reflectedInt;
        Program(int id) {
            this.id = id;
            for (int i=0; i<MATRICES.length; i++) {
                capture(MATRICES[i], 16, matrices[i]);
                // Reproject the actual prior main-camera frame across the same mirror
                // plane. Zero velocity is only used for the first frame in a world.
                capture(PREVIOUS[i], 16, previousMatrices[i]);
            }
            for (String name : SHADOWS) {
                float[] saved=uniforms.readFloats(id,name,16);
                if(saved!=null)original.put(name,saved);
            }
            float[] position = uniforms.readFloats(id, "cameraPosition", 3);
            if (position != null) {
                float[] value = position.clone();
                for (int i=0; i<3; i++) value[i] += delta[i];
                original.put("cameraPosition", position);
                reflected.put("cameraPosition", value);
                // Iris's prior world-camera position must not leak into the reflected
                // velocity pass; use the previous reflected eye at the same plane.
                float[] prior=uniforms.readFloats(id,"previousCameraPosition",3);
                if(prior!=null) {
                    float[] reflectedPrior=previousValid?prior.clone():value.clone();
                    if(previousValid)for(int i=0;i<3;i++)reflectedPrior[i]+=previousDelta[i];
                    original.put("previousCameraPosition",prior);
                    reflected.put("previousCameraPosition",reflectedPrior);
                }
            }
            capture("eyeAltitude", 1, new float[] {(float)eye[1]});
            capture("cameraPositionFract", 3, new float[] {(float)(eye[0]-Math.floor(eye[0])), (float)(eye[1]-Math.floor(eye[1])), (float)(eye[2]-Math.floor(eye[2]))});
            originalInt = uniforms.readInts(id, "cameraPositionInt", 3);
            reflectedInt = new int[] {(int)Math.floor(eye[0]), (int)Math.floor(eye[1]), (int)Math.floor(eye[2])};
            for (String name : DIRECTIONS) {
                float[] value = uniforms.readFloats(id, name, 3);
                if (value == null) continue;
                double[] transformed = MirrorMatrices.transform(viewChange, value[0], value[1], value[2], 0);
                original.put(name, value);
                reflected.put(name, new float[] {(float)transformed[0], (float)transformed[1], (float)transformed[2]});
            }
        }
        void capture(String name, int size, float[] value) {
            float[] saved = uniforms.readFloats(id, name, size);
            if (saved != null) { original.put(name, saved); reflected.put(name, value); }
        }
        void upload(boolean restore) {
            if(!restore && shadowMatrices!=null)
                for(int i=0;i<SHADOWS.length;i++)if(original.containsKey(SHADOWS[i]))reflected.put(SHADOWS[i],shadowMatrices[i]);
            for (Map.Entry<String, float[]> entry : (restore ? original : reflected).entrySet())
                uniforms.writeFloats(id, entry.getKey(), entry.getValue());
            if (originalInt != null) uniforms.writeInts(id, "cameraPositionInt", restore ? originalInt : reflectedInt);
        }
    }
}
