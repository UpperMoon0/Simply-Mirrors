package com.nstut.simplymirrors.math;

import java.util.List;

/**
 * Conservative world-space pyramid through the bounding rectangle of coplanar
 * mirror faces. It only rejects an AABB if all of its corners are strictly
 * outside at least one aperture plane. Designed for chunk-section bounds.
 *
 * This is deliberately a bounding rectangle, not a union of exact face
 * polygons: gaps between coplanar mirror tiles remain visible to the culler.
 */
public final class MirrorApertureVolume {
    private static final double PADDING_BLOCKS = 1.0;
    private final double[][] planes;

    private MirrorApertureVolume(double[][] planes) {
        this.planes = planes;
    }

    public static MirrorApertureVolume from(List<MirrorSurface> faces,
                                             double eyeX, double eyeY, double eyeZ) {
        if (faces == null || faces.isEmpty() || !Double.isFinite(eyeX) || !Double.isFinite(eyeY) || !Double.isFinite(eyeZ)) return null;
        MirrorSurface first = faces.get(0);
        double normalCoord = first.nx * first.cx + first.ny * first.cy + first.nz * first.cz;
        double eyeSide = first.nx * eyeX + first.ny * eyeY + first.nz * eyeZ - normalCoord;
        // The virtual camera must be behind the outward-facing plane.
        if (!Double.isFinite(eyeSide) || eyeSide >= -0.01) return null;

        int u = first.nx != 0 ? 1 : 0;
        int v = first.nz != 0 ? 1 : 2;
        double minU = Double.POSITIVE_INFINITY, maxU = Double.NEGATIVE_INFINITY;
        double minV = Double.POSITIVE_INFINITY, maxV = Double.NEGATIVE_INFINITY;
        double[] corners = new double[12];
        for (MirrorSurface face : faces) {
            if (face.nx != first.nx || face.ny != first.ny || face.nz != first.nz
                    || Math.abs(face.nx * face.cx + face.ny * face.cy + face.nz * face.cz
                                - normalCoord) > 0.001) return null;
            face.writeCorners(corners);
            for (int c = 0; c < 4; c++) {
                double cu = corners[c * 3 + u], cv = corners[c * 3 + v];
                if (!Double.isFinite(cu) || !Double.isFinite(cv)) return null;
                minU = Math.min(minU, cu);
                maxU = Math.max(maxU, cu);
                minV = Math.min(minV, cv);
                maxV = Math.max(maxV, cv);
            }
        }
        if (!(maxU > minU && maxV > minV)) return null;
        double[][] points = new double[4][3];
        for (int i = 0; i < 4; i++) {
            double[] point = points[i];
            point[u] = (i == 1 || i == 2) ? maxU : minU;
            point[v] = (i >= 2) ? maxV : minV;
            if (first.nx != 0) point[0] = normalCoord * first.nx;
            else if (first.ny != 0) point[1] = normalCoord * first.ny;
            else point[2] = normalCoord * first.nz;
        }

        double[] centerDirection = {
            (points[0][0] + points[2][0]) * 0.5 - eyeX,
            (points[0][1] + points[2][1]) * 0.5 - eyeY,
            (points[0][2] + points[2][2]) * 0.5 - eyeZ
        };
        double[][] result = new double[5][4];
        for (int i = 0; i < 4; i++) {
            double[] a = points[i], b = points[(i + 1) % 4];
            double ax = a[0] - eyeX, ay = a[1] - eyeY, az = a[2] - eyeZ;
            double bx = b[0] - eyeX, by = b[1] - eyeY, bz = b[2] - eyeZ;
            double px = ay * bz - az * by;
            double py = az * bx - ax * bz;
            double pz = ax * by - ay * bx;
            double length = Math.sqrt(px * px + py * py + pz * pz);
            if (!Double.isFinite(length) || length < 1.0e-8) return null;
            double sign = (px * centerDirection[0] + py * centerDirection[1]
                            + pz * centerDirection[2]) >= 0 ? 1.0 : -1.0;
            px *= sign / length; py *= sign / length; pz *= sign / length;
            result[i][0] = px; result[i][1] = py; result[i][2] = pz;
            result[i][3] = -(px * eyeX + py * eyeY + pz * eyeZ);
        }
        // The fifth plane excludes geometry behind the physical mirror.
        result[4] = new double[]{first.nx, first.ny, first.nz, -normalCoord};
        return new MirrorApertureVolume(result);
    }

    /** True means the box may contribute a reflected pixel; false is safe to cull. */
    public boolean intersects(double minX, double minY, double minZ,
                              double maxX, double maxY, double maxZ) {
        if (!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(minZ) || !Double.isFinite(maxX) || !Double.isFinite(maxY) || !Double.isFinite(maxZ) || minX > maxX || minY > maxY || minZ > maxZ) return true;
        for (double[] plane : planes) {
            double x = plane[0] >= 0 ? maxX : minX;
            double y = plane[1] >= 0 ? maxY : minY;
            double z = plane[2] >= 0 ? maxZ : minZ;
            if (plane[0] * x + plane[1] * y + plane[2] * z + plane[3] < -PADDING_BLOCKS)
                return false;
        }
        return true;
    }
}
