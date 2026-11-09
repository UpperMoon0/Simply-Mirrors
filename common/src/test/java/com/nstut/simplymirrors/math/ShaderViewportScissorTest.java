package com.nstut.simplymirrors.math;

import org.junit.Test;
import static org.junit.Assert.*;

/** Verify that postprocess clipping never cuts native mirror pixels. */
public class ShaderViewportScissorTest {
    @Test public void projectsToHalfAndQuarterSizeTargetsConservatively() {
        int[] nativeRect={320,200,250,180};
        for(int divisor:new int[]{1,2,4}) {
            int[] box=MirrorCoverage.scaledScissor(nativeRect,1280,720,
                    0,0,1280/divisor,720/divisor);
            assertNotNull(box);
            assertTrue(box[0]<=nativeRect[0]/divisor);
            assertTrue(box[1]<=nativeRect[1]/divisor);
            assertTrue(box[0]+box[2]>=(nativeRect[0]+nativeRect[2])/divisor);
            assertTrue(box[1]+box[3]>=(nativeRect[1]+nativeRect[3])/divisor);
        }
    }
    @Test public void preservesViewportOffsets() {
        int[] box=MirrorCoverage.scaledScissor(new int[]{200,120,120,100},
                1280,720,11,13,640,360);
        assertNotNull(box);
        assertTrue(box[0]>=11 && box[1]>=13);
        assertTrue(box[0]+box[2]<=651 && box[1]+box[3]<=373);
    }
    @Test public void rejectsUnsafeOrFullScreenRegions() {
        assertNull(MirrorCoverage.scaledScissor(null,1280,720,0,0,640,360));
        assertNull(MirrorCoverage.scaledScissor(new int[]{-2,0,100,100},1280,720,0,0,640,360));
        assertNull(MirrorCoverage.scaledScissor(new int[]{0,0,1280,720},1280,720,0,0,640,360));
        assertNull(MirrorCoverage.scaledScissor(new int[]{0,0,0,100},1280,720,0,0,640,360));
    }
}
