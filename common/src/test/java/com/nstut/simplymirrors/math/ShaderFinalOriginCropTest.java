package com.nstut.simplymirrors.math;

import org.junit.Test;
import static org.junit.Assert.*;

/** Conservative final-pass image region, including the required origin pixel. */
public class ShaderFinalOriginCropTest {
    @Test public void originAndPaddedMirrorAreBothCovered() {
        int[] box=ShaderFinalOriginCrop.rectangle(
            700,260,420,400,1920,1080,0,0,1920,1080,96);
        assertArrayEquals(new int[]{0,0,1216,756},box);
        assertTrue(box[0]<=0 && box[1]<=0);
        assertTrue(box[0]+box[2]>=1120);
        assertTrue(box[1]+box[3]>=660);
    }

    @Test public void offsetOrScaledViewportMustRemainUncropped() {
        assertNull(ShaderFinalOriginCrop.rectangle(
            700,260,420,400,1920,1080,10,0,1920,1080,96));
        assertNull(ShaderFinalOriginCrop.rectangle(
            700,260,420,400,1920,1080,0,10,1920,1080,96));
        assertNull(ShaderFinalOriginCrop.rectangle(
            700,260,420,400,1920,1080,0,0,960,540,96));
    }

    @Test public void invalidBoundsOrNearlyFullScreenFallBack() {
        assertNull(ShaderFinalOriginCrop.rectangle(
            1,1,1910,1070,1920,1080,0,0,1920,1080,96));
        assertNull(ShaderFinalOriginCrop.rectangle(
            1800,500,200,200,1920,1080,0,0,1920,1080,96));
        assertNull(ShaderFinalOriginCrop.rectangle(
            -1,100,20,20,1920,1080,0,0,1920,1080,96));
        assertNull(ShaderFinalOriginCrop.rectangle(
            700,260,420,400,1920,1080,0,0,1920,1080,-1));
        assertNull(ShaderFinalOriginCrop.rectangle(
            Integer.MAX_VALUE,260,420,400,1920,1080,0,0,1920,1080,96));
    }

    @Test public void shaderLabelMustBeTheExactFinalProgram() {
        assertTrue(ShaderFinalOriginCrop.isPinnedFinal("final"));
        assertTrue(ShaderFinalOriginCrop.isPinnedFinal("FINAL"));
        assertFalse(ShaderFinalOriginCrop.isPinnedFinal("final2"));
        assertFalse(ShaderFinalOriginCrop.isPinnedFinal(null));
        assertFalse(ShaderFinalOriginCrop.isPinnedFinal("composite7"));
    }

    @Test public void doesNotAssumeAGainWhenPreservingOriginWouldCostTooMuch() {
        assertNull(ShaderFinalOriginCrop.rectangle(
            1200,700,550,280,1920,1080,0,0,1920,1080,96));
        assertArrayEquals(new int[]{0,0,864,564},
            ShaderFinalOriginCrop.rectangle(
                500,250,300,250,1920,1080,0,0,1920,1080,64));
    }
}
