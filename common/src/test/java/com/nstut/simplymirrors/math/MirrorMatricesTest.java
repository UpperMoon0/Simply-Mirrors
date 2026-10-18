package com.nstut.simplymirrors.math;
import org.junit.Test;
import static org.junit.Assert.*;

public class MirrorMatricesTest {
    private static final float[] VIEW = {-1,0,0,0, 0,1,0,0, 0,0,-1,0, 0,0,0,1};
    private static final float[] PROJECTION = {1.73205f,0,0,0, 0,1.73205f,0,0, 0,0,-1.002002f,-1, 0,0,-.2002002f,0};
    @Test public void reflectionMatrixAndVectorAgree() {
        for(double[] normal:new double[][]{{1,0,0},{0,0,1},{0,1,0},{.6,.8,0}}) {
            double[] vector=ReflectionTransform.reflectVector(2,3,4,normal[0],normal[1],normal[2]);
            double[] matrix=MirrorMatrices.transform(ReflectionTransform.matrix(normal[0],normal[1],normal[2]),2,3,4,0);
            assertArrayEquals(new double[]{vector[0],vector[1],vector[2],0},matrix,1e-5);
        }
    }
    @Test public void doubleReflectionIsIdentity() {
        float[] r=ReflectionTransform.matrix(.6,.8,0);
        float[] identity=MirrorMatrices.multiply(r,r);
        for(int i=0;i<16;i++)assertEquals(i%5==0?1:0,identity[i],1e-5);
    }
    @Test public void inverseProjectionRoundTrips() {
        float[] product=MirrorMatrices.multiply(PROJECTION,MirrorMatrices.inverse(PROJECTION));
        for(int i=0;i<16;i++)assertEquals(i%5==0?1:0,product[i],1e-5);
    }
    @Test public void reflectionPreservesScreenCoordinatesOnGlass() {
        float[] reflectedView=MirrorMatrices.multiply(VIEW,ReflectionTransform.matrix(0,0,-1));
        float[] clipped=MirrorMatrices.oblique(PROJECTION,reflectedView,0,0,-1,0,0,-3.875,false);
        double[] main=MirrorMatrices.transform(MirrorMatrices.multiply(PROJECTION,VIEW),.25,.5,3.875,1);
        double[] mirror=MirrorMatrices.transform(MirrorMatrices.multiply(clipped,reflectedView),.25,.5,-3.875,1);
        assertEquals(main[0]/main[3],mirror[0]/mirror[3],1e-6);
        assertEquals(main[1]/main[3],mirror[1]/mirror[3],1e-6);
    }
    @Test public void wallBehindMirrorIsClippedAndRoomInFrontSurvives() {
        float[] view=MirrorMatrices.multiply(VIEW,ReflectionTransform.matrix(0,0,-1));
        float[] projection=MirrorMatrices.oblique(PROJECTION,view,0,0,-1,0,0,-3.875,false);
        float[] vp=MirrorMatrices.multiply(projection,view);
        double[] wall=MirrorMatrices.transform(vp,0,0,-3.25,1);
        double[] room=MirrorMatrices.transform(vp,0,0,-4.75,1);
        assertTrue("Wall must fail the oblique near plane",wall[2]+wall[3]<0);
        assertTrue("Room must survive the oblique near plane",room[2]+room[3]>0);
    }
    @Test public void zeroToOneProjectionClipsWallAndPreservesGlassAlignment() {
        float[] projection=PROJECTION.clone();
        for(int col=0;col<4;col++)projection[col*4+2]=(PROJECTION[col*4+2]+PROJECTION[col*4+3])*.5f;
        float[] view=MirrorMatrices.multiply(VIEW,ReflectionTransform.matrix(0,0,-1));
        float[] clipped=MirrorMatrices.oblique(projection,view,0,0,-1,0,0,-3.875,true);
        float[] vp=MirrorMatrices.multiply(clipped,view);
        double[] wall=MirrorMatrices.transform(vp,0,0,-3.25,1),room=MirrorMatrices.transform(vp,0,0,-4.75,1);
        assertTrue("Wall fails zero-to-one near plane",wall[2]<0);
        assertTrue("Room survives zero-to-one near plane",room[2]>0);
        double[] main=MirrorMatrices.transform(MirrorMatrices.multiply(projection,VIEW),.25,.5,3.875,1);
        double[] mirror=MirrorMatrices.transform(vp,.25,.5,-3.875,1);
        assertEquals(main[0]/main[3],mirror[0]/mirror[3],1e-6);
        assertEquals(main[1]/main[3],mirror[1]/mirror[3],1e-6);
    }
    @Test public void horizontalPlaneKeysMergeOnlyCoplanarSameFacingCells() {
        assertEquals(new MirrorSurface(0,0,0,0,-1).planeKey(),new MirrorSurface(1,1,0,0,-1).planeKey());
        assertNotEquals(new MirrorSurface(0,0,0,0,-1).planeKey(),new MirrorSurface(0,0,1,0,-1).planeKey());
        assertNotEquals(new MirrorSurface(0,0,0,0,-1).planeKey(),new MirrorSurface(0,0,0,0,1).planeKey());
    }
    @Test public void mirrorSurfaceCoversTheEntireOutwardCubeFace() {
        for(int[] n:new int[][]{{0,-1},{0,1},{-1,0},{1,0}}) {
            MirrorSurface surface=new MirrorSurface(2,3,4,n[0],n[1]);
            assertEquals(2.5+n[0]*.5,surface.cx,1e-9);
            assertEquals(4.5+n[1]*.5,surface.cz,1e-9);
            double minY=Double.POSITIVE_INFINITY,maxY=-minY,minSide=minY,maxSide=-minY;
            for(double[] corner:surface.corners()) {
                double dx=corner[0]-surface.cx,dz=corner[2]-surface.cz;
                assertEquals(.001,dx*n[0]+dz*n[1],1e-9);
                double side=-n[1]*dx+n[0]*dz;
                minSide=Math.min(minSide,side);maxSide=Math.max(maxSide,side);
                minY=Math.min(minY,corner[1]);maxY=Math.max(maxY,corner[1]);
            }
            assertEquals(3,minY,1e-9);assertEquals(4,maxY,1e-9);
            assertEquals(-.5,minSide,1e-9);assertEquals(.5,maxSide,1e-9);
        }
    }
    @Test(expected=IllegalArgumentException.class) public void singularMatricesAreRejected() { MirrorMatrices.inverse(new float[16]); }
}
