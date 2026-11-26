package com.nstut.simplymirrors.math;

import org.junit.Test;
import static org.junit.Assert.*;

public class ShaderFinalDualCropTest {
    @Test public void nativeMirrorRectangleAvoidsOriginAndRetainsHalo() {
        assertArrayEquals(new int[]{663,203,625,626},
                ShaderFinalDualCrop.rectangle("final",759,299,433,434,
                        1920,1061,0,0,1920,1061));
    }
    @Test public void originOverlapMustNotBeRenderedTwice() {
        assertNull(ShaderFinalDualCrop.rectangle("final",0,0,400,400,
                1920,1080,0,0,1920,1080));
        assertNull(ShaderFinalDualCrop.rectangle("final",25,25,100,100,
                1920,1080,0,0,1920,1080));
    }
    @Test public void unrecognizedProgramAndScaledOrOffsetViewportFailClosed() {
        assertNull(ShaderFinalDualCrop.rectangle("composite",759,299,433,434,
                1920,1080,0,0,1920,1080));
        assertNull(ShaderFinalDualCrop.rectangle("final",759,299,433,434,
                1920,1080,0,0,960,540));
        assertNull(ShaderFinalDualCrop.rectangle("final",759,299,433,434,
                1920,1080,10,0,1920,1080));
    }
    @Test public void rejectsInvalidAndFullCoverage() {
        assertNull(ShaderFinalDualCrop.rectangle("final",1890,299,433,434,
                1920,1080,0,0,1920,1080));
        assertNull(ShaderFinalDualCrop.rectangle("final",759,299,-1,434,
                1920,1080,0,0,1920,1080));
        assertNull(ShaderFinalDualCrop.rectangle("final",0,0,1920,1080,
                1920,1080,0,0,1920,1080));
    }
}
