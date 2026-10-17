/*
 * Adapted from Immersive Portals CE: Mirror.mirroredVec and
 * TransformationManager.getMirrorTransformation. Copyright 2020 qouteall and contributors.
 * SPDX-License-Identifier: Apache-2.0
 * Changes: primitive Java 8 representation, no Minecraft/JOML/portal dependencies.
 */
package com.nstut.simplymirrors.math;

public final class ImmersiveMirrorTransform {
    private ImmersiveMirrorTransform() { }

    /** CE's v + normal * (v dot normal * -2). The caller provides a unit normal. */
    public static double[] mirroredVector(double x, double y, double z, double nx, double ny, double nz) {
        double len = x * nx + y * ny + z * nz;
        return new double[] {x + nx * (len * -2), y + ny * (len * -2), z + nz * (len * -2)};
    }

    /** CE's Matrix4f.reflection(normal, 0), in column-major OpenGL layout. */
    public static float[] matrix(double nx, double ny, double nz) {
        double[] n = {nx, ny, nz};
        float[] result = new float[16];
        for (int col = 0; col < 3; col++) for (int row = 0; row < 3; row++) {
            result[col * 4 + row] = (float) ((row == col ? 1 : 0) - 2 * n[row] * n[col]);
        }
        result[15] = 1;
        return result;
    }
}
