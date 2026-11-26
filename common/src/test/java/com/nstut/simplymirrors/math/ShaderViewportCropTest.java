package com.nstut.simplymirrors.math;

import org.junit.Test;
import static org.junit.Assert.*;

public class ShaderViewportCropTest {
    @Test public void nativeAndScaledViewportsPreserveBounds() {
        assertArrayEquals(new int[]{300,150,400,400},
            ShaderViewportCrop.rectangle(400,250,200,200,1920,1080,0,0,1920,1080,100));
        assertArrayEquals(new int[]{150,75,200,200},
            ShaderViewportCrop.rectangle(400,250,200,200,1920,1080,0,0,960,540,100));
        assertArrayEquals(new int[]{160,95,200,200},
            ShaderViewportCrop.rectangle(400,250,200,200,1920,1080,10,20,960,540,100));
    }

    @Test public void wideMirrorGroupsSkipShaderQueriesBeforeTheGpuPath() {
        // A single narrow mirror retains a useful clipped region at 512px pad.
        assertNotNull(ShaderViewportCrop.rectangle(735,303,435,435,
            1920,1061,0,0,1920,1061,512));
        // Nine large coplanar mirrors fill almost the entire native viewport.
        // Per-quad OpenGL state reads are a measurable regression here.
        assertNull(ShaderViewportCrop.rectangle(526,95,816,831,
            1920,1061,0,0,1920,1061,512));
    }
    @Test public void conservativeFallbacksCannotClipUnknownCoverage() {
        assertNull(ShaderViewportCrop.rectangle(0,0,1920,1080,1920,1080,0,0,1920,1080,256));
        assertNull(ShaderViewportCrop.rectangle(0,0,100,100,0,1080,0,0,1920,1080,256));
        assertNull(ShaderViewportCrop.rectangle(0,0,100,100,1920,1080,0,0,0,1080,256));
        assertNull(ShaderViewportCrop.rectangle(0,0,100,100,1920,1080,0,0,1920,1080,-1));
    }
}
