package com.nstut.simplymirrors.math;
import org.junit.Test;
import static org.junit.Assert.*;

public class MirrorMatricesTest {
    private static final float[] VIEW = {-1,0,0,0, 0,1,0,0, 0,0,-1,0, 0,0,0,1};
    private static final float[] PROJECTION = {1.73205f,0,0,0, 0,1.73205f,0,0, 0,0,-1.002002f,-1, 0,0,-.2002002f,0};
    @Test public void ceReflectionMatrixAndVectorAgree() {
        for(double[] normal:new double[][]{{1,0,0},{0,0,1},{0,1,0},{.6,.8,0}}) {
            double[] vector=ImmersiveMirrorTransform.mirroredVector(2,3,4,normal[0],normal[1],normal[2]);
            double[] matrix=MirrorMatrices.transform(ImmersiveMirrorTransform.matrix(normal[0],normal[1],normal[2]),2,3,4,0);
            assertArrayEquals(new double[]{vector[0],vector[1],vector[2],0},matrix,1e-5);
        }
    }
    @Test public void doubleReflectionIsIdentity() {
        float[] r=ImmersiveMirrorTransform.matrix(.6,.8,0);
        float[] identity=MirrorMatrices.multiply(r,r);
        for(int i=0;i<16;i++)assertEquals(i%5==0?1:0,identity[i],1e-5);
    }
    @Test public void inverseProjectionRoundTrips() {
        float[] product=MirrorMatrices.multiply(PROJECTION,MirrorMatrices.inverse(PROJECTION));
        for(int i=0;i<16;i++)assertEquals(i%5==0?1:0,product[i],1e-5);
    }
    @Test public void reflectionPreservesScreenCoordinatesOnGlass() {
        float[] reflectedView=MirrorMatrices.multiply(VIEW,ImmersiveMirrorTransform.matrix(0,0,-1));
        float[] clipped=MirrorMatrices.oblique(PROJECTION,reflectedView,0,0,-1,0,0,-3.875,false);
        double[] main=MirrorMatrices.transform(MirrorMatrices.multiply(PROJECTION,VIEW),.25,.5,3.875,1);
        double[] mirror=MirrorMatrices.transform(MirrorMatrices.multiply(clipped,reflectedView),.25,.5,-3.875,1);
        assertEquals(main[0]/main[3],mirror[0]/mirror[3],1e-6);
        assertEquals(main[1]/main[3],mirror[1]/mirror[3],1e-6);
    }
    @Test public void wallBehindMirrorIsClippedAndRoomInFrontSurvives() {
        float[] view=MirrorMatrices.multiply(VIEW,ImmersiveMirrorTransform.matrix(0,0,-1));
        float[] projection=MirrorMatrices.oblique(PROJECTION,view,0,0,-1,0,0,-3.875,false);
        float[] vp=MirrorMatrices.multiply(projection,view);
        double[] wall=MirrorMatrices.transform(vp,0,0,-3.25,1);
        double[] room=MirrorMatrices.transform(vp,0,0,-4.75,1);
        assertTrue("Wall must fail the oblique near plane",wall[2]+wall[3]<0);
        assertTrue("Room must survive the oblique near plane",room[2]+room[3]>0);
    }
    @Test public void horizontalPlaneKeysMergeOnlyCoplanarSameFacingCells() {
        assertEquals(new MirrorSurface(0,0,0,0,-1).planeKey(),new MirrorSurface(1,1,0,0,-1).planeKey());
        assertNotEquals(new MirrorSurface(0,0,0,0,-1).planeKey(),new MirrorSurface(0,0,1,0,-1).planeKey());
        assertNotEquals(new MirrorSurface(0,0,0,0,-1).planeKey(),new MirrorSurface(0,0,0,0,1).planeKey());
    }
    @Test(expected=IllegalArgumentException.class) public void singularMatricesAreRejected() { MirrorMatrices.inverse(new float[16]); }
}
