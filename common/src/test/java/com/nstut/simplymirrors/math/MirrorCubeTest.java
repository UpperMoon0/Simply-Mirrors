package com.nstut.simplymirrors.math;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class MirrorCubeTest {
    @Test public void everyCubeFaceHasUnitAreaOutwardWindingAndCorrectPlane() {
        MirrorSurface[] faces=MirrorSurface.faces(2,3,4);
        assertEquals(6,faces.length);Set<String> keys=new HashSet<String>();
        for(MirrorSurface face:faces) {
            assertTrue(keys.add(face.planeKey()));
            double[][] corners=face.corners();
            for(double[] corner:corners) {
                assertEquals(.001,face.plane().signedDistance(corner[0],corner[1],corner[2]),1e-9);
                assertTrue(corner[0]>=1.999&&corner[0]<=3.001);
                assertTrue(corner[1]>=2.999&&corner[1]<=4.001);
                assertTrue(corner[2]>=3.999&&corner[2]<=5.001);
            }
            double ax=corners[1][0]-corners[0][0],ay=corners[1][1]-corners[0][1],az=corners[1][2]-corners[0][2];
            double bx=corners[2][0]-corners[1][0],by=corners[2][1]-corners[1][1],bz=corners[2][2]-corners[1][2];
            assertArrayEquals(new double[]{face.nx,face.ny,face.nz},new double[]{ay*bz-az*by,az*bx-ax*bz,ax*by-ay*bx},1e-9);
        }
    }
    @Test public void everyFaceReflectsAcrossItsOwnOutwardPlane() {
        for(MirrorSurface face:MirrorSurface.faces(2,3,4)) {
            double ex=face.cx+face.nx*3,ey=face.cy+face.ny*3,ez=face.cz+face.nz*3;
            assertTrue(face.plane().isFrontFacing(ex,ey,ez,.025));
            double[] reflected=face.plane().reflectPoint(ex,ey,ez);
            assertEquals(-3,face.plane().signedDistance(reflected[0],reflected[1],reflected[2]),1e-9);
            assertArrayEquals(new double[]{ex,ey,ez},face.plane().reflectPoint(reflected[0],reflected[1],reflected[2]),1e-9);
        }
    }
    @Test public void floorAndCeilingMergeCoplanarCellsWithoutMergingOppositeFaces() {
        assertEquals(new MirrorSurface(0,3,0,0,1,0).planeKey(),new MirrorSurface(1,3,2,0,1,0).planeKey());
        assertEquals(new MirrorSurface(0,3,0,0,-1,0).planeKey(),new MirrorSurface(1,3,2,0,-1,0).planeKey());
        assertNotEquals(new MirrorSurface(0,3,0,0,1,0).planeKey(),new MirrorSurface(0,4,0,0,-1,0).planeKey());
    }
}
