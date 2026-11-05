package com.nstut.simplymirrors.math;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

/** Regression: never clip a visible reflection or return invalid GL scissor bounds. */
public class MirrorCoverageTest {
    private static float[] identity() {
        return new float[]{1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    }
    private static void containsCorners(int[] rect, float[] projection,
                                        MirrorSurface surface, int width, int height) {
        assertNotNull(rect);
        double[] corners=new double[12];
        surface.writeCorners(corners);
        for(int i=0;i<4;i++) {
            double[] clip=MirrorMatrices.transform(projection,
                    corners[i*3],corners[i*3+1],corners[i*3+2],1);
            double px=(clip[0]/clip[3]*.5+.5)*width;
            double py=(clip[1]/clip[3]*.5+.5)*height;
            assertTrue("Projected x was clipped: "+px, px >= rect[0] && px <= rect[0]+rect[2]);
            assertTrue("Projected y was clipped: "+py, py >= rect[1] && py <= rect[1]+rect[3]);
        }
    }

    @Test public void everyCubeFaceIsFullyContained() {
        float[] projection=identity();
        for(MirrorSurface face:MirrorSurface.faces(0,0,0)) {
            int[] rect=MirrorCoverage.scissor(projection,0,0,0,1280,720,
                    Collections.singletonList(face));
            containsCorners(rect,projection,face,1280,720);
            assertTrue(rect[0]>=0 && rect[1]>=0);
            assertTrue(rect[0]+rect[2]<=1280);
            assertTrue(rect[1]+rect[3]<=720);
        }
    }

    @Test public void unionCoversEveryFaceRatherThanOnlyTheFirstTile() {
        float[] vp=identity();
        MirrorSurface one=new MirrorSurface(0,0,0,0,0,-1);
        MirrorSurface other=new MirrorSurface(-1,0,0,0,0,-1);
        int[] rect=MirrorCoverage.scissor(vp,0,0,0,1280,720,Arrays.asList(one,other));
        containsCorners(rect,vp,one,1280,720);
        containsCorners(rect,vp,other,1280,720);
    }

    @Test public void tinyMirrorRetainsFullResolutionWithWidePadding() {
        float[] projection=identity();
        projection[0]=.2f;projection[5]=.2f;
        MirrorSurface face=new MirrorSurface(0,0,0,0,0,-1);
        int[] rect=MirrorCoverage.scissor(projection,0,0,0,1280,720,
                Collections.singletonList(face));
        assertArrayEquals(new int[]{544,264,320,264},rect);
        containsCorners(rect,projection,face,1280,720);
    }

    @Test public void highResolutionUsesResolutionScaledPadding() {
        float[] projection=identity();
        projection[0]=.2f;projection[5]=.2f;
        MirrorSurface face=new MirrorSurface(0,0,0,0,0,-1);
        int[] rect=MirrorCoverage.scissor(projection,0,0,0,3840,2160,
                Collections.singletonList(face));
        assertNotNull(rect);
        // max(width,height)/16 => 240 pixel margin rather than the 96px floor.
        double left=(.5)*3840, lower=(.5)*2160;
        assertTrue(left - rect[0] >= 239);
        assertTrue(lower - rect[1] >= 239);
        containsCorners(rect,projection,face,3840,2160);
    }

    @Test public void nearPlaneCrossingFallsBackToFullScreen() {
        float[] projection=identity();
        // w=z: cube face z=0 intersects the projection singularity.
        projection[15]=0;
        projection[11]=1;
        assertNull(MirrorCoverage.scissor(projection,0,0,0,1280,720,
                Collections.singletonList(new MirrorSurface(0,0,0,0,0,-1))));
    }

    @Test public void outOfDepthBoundsFallsBackToFullScreen() {
        assertNull(MirrorCoverage.scissor(identity(),0,0,0,1280,720,
                Collections.singletonList(new MirrorSurface(0,0,3,0,0,-1))));
    }

    @Test public void fullScreenCoverageFallsBackWithoutQualityLoss() {
        MirrorSurface a=new MirrorSurface(-1,-1,0,0,0,-1);
        MirrorSurface b=new MirrorSurface(0,0,0,0,0,-1);
        assertNull(MirrorCoverage.scissor(identity(),0,0,0,1280,720,Arrays.asList(a,b)));
    }

    @Test public void emptyAndInvalidViewportsFallBack() {
        assertNull(MirrorCoverage.scissor(identity(),0,0,0,1280,720,
                Collections.<MirrorSurface>emptyList()));
        assertNull(MirrorCoverage.scissor(identity(),0,0,0,0,720,
                Collections.singletonList(new MirrorSurface(0,0,0,0,0,-1))));
    }
}
