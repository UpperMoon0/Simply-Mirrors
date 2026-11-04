package com.nstut.simplymirrors.math;

/**
 * Real previous-frame main-camera pose, reflected across the *same* mirror
 * plane as the current render. No shader temporal state is advanced here.
 * Each Minecraft render adapter owns one instance and resets it on world exit.
 */
public final class MirrorTemporalHistory {
    private Frame last;

    public Frame advance(float[] view, float[] projection, double eyeX, double eyeY, double eyeZ) {
        Frame current=new Frame(view,projection,eyeX,eyeY,eyeZ,false);
        Frame previous=last==null?current:last;
        last=new Frame(view,projection,eyeX,eyeY,eyeZ,true);
        return previous;
    }

    public void reset() { last=null; }

    public static final class Frame {
        private final float[] view, projection;
        private final double x,y,z;
        private final boolean valid;
        private Frame(float[] view,float[] projection,double x,double y,double z,boolean valid) {
            this.valid=valid;
            this.view=view.clone();this.projection=projection.clone();
            this.x=x;this.y=y;this.z=z;
        }
        /** Compute historical reflected pose for this exact static plane. */
        public Pose reflect(MirrorSurface surface) {
            float[] reflectedView=MirrorMatrices.multiply(view,
                    ReflectionTransform.matrix(surface.nx,surface.ny,surface.nz));
            double[] eye=surface.plane().reflectPoint(x,y,z);
            return new Pose(reflectedView,projection.clone(),new double[]{x,y,z},eye,valid);
        }
    }

    public static final class Pose {
        public final float[] view,projection;
        public final double[] mainEye,eye;
        public final boolean valid;
        private Pose(float[] view,float[] projection,double[] mainEye,double[] eye,boolean valid) {
            this.view=view;this.projection=projection;
            this.mainEye=mainEye;this.eye=eye;this.valid=valid;
        }
    }
}
