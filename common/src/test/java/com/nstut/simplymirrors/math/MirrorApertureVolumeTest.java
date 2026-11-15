package com.nstut.simplymirrors.math;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class MirrorApertureVolumeTest {
    @Test public void visibleBehindAndOutsideMirror() {
        MirrorApertureVolume volume = MirrorApertureVolume.from(
                Collections.singletonList(new MirrorSurface(0, 0, 0, 0, 0, 1)),
                .5, .5,  -5.0);
        // -5 is behind +Z, i.e. the reflected camera, not the true eye.
        assertNotNull(volume);
        assertTrue(volume.intersects(0, 0, .5, 1, 1, 1.5));
        assertTrue(volume.intersects(-.5, -.5, -1, 1, 1, 1));
        assertFalse(volume.intersects(70, 0, 4, 86, 16, 20));
        assertFalse(volume.intersects(0, 0, -40, 1, 1, -20));
    }

    @Test public void distantObjectsStayIfProjectedThroughMirror() {
        MirrorApertureVolume volume = MirrorApertureVolume.from(
                Collections.singletonList(new MirrorSurface(0, 0, 0, 0, 0, 1)),
                .5, .5, -5);
        assertNotNull(volume);
        assertTrue(volume.intersects(-1, -1, 20, 2, 2, 36));
        assertFalse(volume.intersects(80, 0, 20, 96, 16, 36));
    }

    @Test public void coplanarGapsAndMultipleFacesRemainConservative() {
        MirrorApertureVolume volume = MirrorApertureVolume.from(
                Arrays.asList(new MirrorSurface(0, 0, 0, 0, 0, 1),
                              new MirrorSurface(6, 0, 0, 0, 0, 1)),
                .5, .5, -5);
        assertNotNull(volume);
        assertTrue("Bounding rectangle must include gaps", volume.intersects(2, 0, 10, 3, 1, 12));
        assertTrue(volume.intersects(6, 0, 1, 7, 1, 2));
    }

    @Test public void invalidEyeOrNonCoplanarFacesDisableCulling() {
        assertNull(MirrorApertureVolume.from(
                Collections.singletonList(new MirrorSurface(0, 0, 0, 0, 0, 1)),
                .5, .5, 3));
        assertNull(MirrorApertureVolume.from(
                Arrays.asList(new MirrorSurface(0, 0, 0, 0, 0, 1),
                              new MirrorSurface(0, 0, 1, 0, 0, 1)),
                .5, .5, -5));
    }

    /**
     * Every point reached by a ray through the actual mirror surface must
     * survive section-level aperture rejection, regardless of face normal,
     * viewpoint skew or distance behind the plane.
     */
    @Test public void everyReflectedCameraRayThroughMirrorIsConservativelyVisible() {
        for (MirrorSurface face : MirrorSurface.faces(0, 0, 0)) {
            double[][] corners = face.corners();
            for (double lateral : new double[]{-3, 0, 3}) {
                double eyeX = face.cx - 5 * face.nx + (face.nx == 0 ? lateral : 0);
                double eyeY = face.cy - 5 * face.ny + (face.ny == 0 ? lateral : 0);
                double eyeZ = face.cz - 5 * face.nz + (face.nz == 0 ? lateral : 0);
                MirrorApertureVolume volume = MirrorApertureVolume.from(
                    Collections.singletonList(face), eyeX, eyeY, eyeZ);
                assertNotNull(volume);
                for (int u = 0; u <= 10; u++)
                    for (int v = 0; v <= 10; v++) {
                        double tu = u / 10.0, tv = v / 10.0;
                        double[] surface = new double[3];
                        for (int axis = 0; axis < 3; axis++)
                            surface[axis] = corners[0][axis] * (1-tu)*(1-tv)
                                + corners[1][axis] * tu*(1-tv)
                                + corners[2][axis] * tu*tv
                                + corners[3][axis] * (1-tu)*tv;
                        for (double distance : new double[]{1.01, 1.5, 10, 60}) {
                            double px = eyeX + distance*(surface[0] - eyeX);
                            double py = eyeY + distance*(surface[1] - eyeY);
                            double pz = eyeZ + distance*(surface[2] - eyeZ);
                            assertTrue("Ray rejected face " + face.planeKey()
                                + " ray " + u + "/" + v + " t=" + distance,
                                volume.intersects(px-.01, py-.01, pz-.01,
                                    px+.01, py+.01, pz+.01));
                        }
                    }
            }
        }
    }

    @Test public void invalidNumericInputsFailOpen() {
        MirrorSurface face = new MirrorSurface(0,0,0,0,0,1);
        assertNull(MirrorApertureVolume.from(Collections.singletonList(face), Double.NaN, .5, -5));
        MirrorApertureVolume volume = MirrorApertureVolume.from(Collections.singletonList(face), .5, .5, -5);
        assertTrue(volume.intersects(Double.NaN, 0, 0, 1, 1, 1));
        assertTrue(volume.intersects(2, 0, 0, 1, 1, 1));
    }
    @Test public void allSixFaceOrientationsProduceValidCones() {
        for (MirrorSurface face : MirrorSurface.faces(0, 0, 0)) {
            MirrorApertureVolume volume = MirrorApertureVolume.from(
                    Collections.singletonList(face),
                    face.cx - 5 * face.nx,
                    face.cy - 5 * face.ny,
                    face.cz - 5 * face.nz);
            assertNotNull(face.planeKey(), volume);
            assertTrue(volume.intersects(0, 0, 0, 1, 1, 1));
            assertTrue(volume.intersects(face.cx + 2 * face.nx - .5,
                    face.cy + 2 * face.ny - .5, face.cz + 2 * face.nz - .5,
                    face.cx + 2 * face.nx + .5,
                    face.cy + 2 * face.ny + .5, face.cz + 2 * face.nz + .5));
        }
    }
}
