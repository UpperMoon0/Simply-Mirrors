package com.nstut.simplymirrors.math;

/** Immutable plane and reflection math, shared with Java 8-era Minecraft. */
public final class MirrorPlane {
    private final double x, y, z, nx, ny, nz;

    public MirrorPlane(double x, double y, double z, double nx, double ny, double nz) {
        requireFinite(x, y, z, nx, ny, nz);
        double length = Math.hypot(Math.hypot(nx, ny), nz);
        if (!Double.isFinite(length) || length < 1.0e-12) {
            throw new IllegalArgumentException("Mirror normal must have a finite, nonzero length");
        }
        this.x = x; this.y = y; this.z = z;
        this.nx = nx / length; this.ny = ny / length; this.nz = nz / length;
    }

    public double signedDistance(double px, double py, double pz) {
        requireFinite(px, py, pz);
        return (px - x) * nx + (py - y) * ny + (pz - z) * nz;
    }

    public double[] reflectPoint(double px, double py, double pz) {
        requireFinite(px, py, pz);
        double[] v = ImmersiveMirrorTransform.mirroredVector(px - x, py - y, pz - z, nx, ny, nz);
        return new double[] {v[0] + x, v[1] + y, v[2] + z};
    }

    public double[] reflectDirection(double dx, double dy, double dz) {
        requireFinite(dx, dy, dz);
        return ImmersiveMirrorTransform.mirroredVector(dx, dy, dz, nx, ny, nz);
    }

    /** The viewer must be on the front side of the surface, beyond the numerical tolerance. */
    public boolean isFrontFacing(double px, double py, double pz, double epsilon) {
        requireFinite(epsilon);
        if (epsilon < 0) throw new IllegalArgumentException("Tolerance cannot be negative");
        return signedDistance(px, py, pz) > epsilon;
    }

    private static void requireFinite(double... values) {
        for (double value : values) {
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Coordinates must be finite");
        }
    }
}
