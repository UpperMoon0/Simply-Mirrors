package com.nstut.simplymirrors.math;

import org.junit.Test;
import static org.junit.Assert.*;

/** Tests culling-only crop; the displayed projection must never change. */
public class ReflectionFrustumCropTest {
    private static final float[] ID={
        1,0,0,0,
        0,1,0,0,
        0,0,1,0,
        0,0,0,1
    };

    @Test public void rejectsMissingAndUnsafeBounds() {
        assertArrayEquals(ID,ReflectionFrustumCrop.crop(ID,1280,720,null),0f);
        assertArrayEquals(ID,ReflectionFrustumCrop.crop(ID,1280,720,new int[]{-2,0,100,100}),0f);
        assertArrayEquals(ID,ReflectionFrustumCrop.crop(ID,1280,720,new int[]{0,0,1280,720}),0f);
        assertArrayEquals(ID,ReflectionFrustumCrop.crop(ID,1280,720,new int[]{1200,0,200,100}),0f);
    }

    @Test public void preservesVisibleRectangleWithConservativePadding() {
        int[] rect={540,300,200,110};
        float[] cropped=ReflectionFrustumCrop.crop(ID,1280,720,rect);
        for(int[] corner:new int[][]{{540,300},{740,300},{540,410},{740,410}}) {
            double x=2.0*corner[0]/1280-1;
            double y=2.0*corner[1]/720-1;
            double[] clip=MirrorMatrices.transform(cropped,x,y,0,1);
            assertTrue("Visible mirror x must survive frustum crop",Math.abs(clip[0]/clip[3])<1);
            assertTrue("Visible mirror y must survive frustum crop",Math.abs(clip[1]/clip[3])<1);
        }
        assertTrue(cropped[0]>1); // actual tighter than the native frustum
        assertTrue(cropped[5]>1);
    }

    @Test public void doesNotAlterNearFarDepthMapping() {
        float[] nativeProjection=ID.clone();
        nativeProjection[10]=-.7f;
        nativeProjection[14]=-1.7f;
        float[] cropped=ReflectionFrustumCrop.crop(nativeProjection,1280,720,new int[]{600,320,80,80});
        assertEquals(nativeProjection[10],cropped[10],0f);
        assertEquals(nativeProjection[14],cropped[14],0f);
        assertEquals(nativeProjection[11],cropped[11],0f);
    }
}
