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
