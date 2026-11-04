package com.nstut.simplymirrors.math;

/** A coplanar surface drawn by the reflection compositor. */
public interface MirrorGeometry {
    double[][] corners();
    /** Write four (x,y,z) corners into caller-owned 12-double scratch storage. */
    void writeCorners(double[] out);
}
