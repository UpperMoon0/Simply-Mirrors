/* Copyright (c) 2026 NsTut. SPDX-License-Identifier: MIT */
package com.nstut.simplymirrors.math;

/** Planar reflection using the normal component of a vector, independent of any portal mod. */
public final class ReflectionTransform {
    private ReflectionTransform() { }

    /** Reverse the component parallel to a unit plane normal; preserve the tangent component. */
    public static double[] reflectVector(double x,double y,double z,double nx,double ny,double nz) {
        double twiceNormalComponent=2.0*(nx*x+ny*y+nz*z);
        return new double[]{x-twiceNormalComponent*nx,y-twiceNormalComponent*ny,z-twiceNormalComponent*nz};
    }

    /** Build a column-major linear transform by reflecting the three Cartesian basis vectors. */
    public static float[] matrix(double nx,double ny,double nz) {
        float[] transform=new float[16];
        for(int axis=0;axis<3;axis++) {
            double[] basis=reflectVector(axis==0?1:0,axis==1?1:0,axis==2?1:0,nx,ny,nz);
            for(int component=0;component<3;component++)transform[4*axis+component]=(float)basis[component];
        }
        transform[15]=1;
        return transform;
    }
}
