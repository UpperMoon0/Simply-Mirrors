package com.nstut.simplymirrors.math;

import org.junit.Test;
import static org.junit.Assert.*;

/** Previous-frame pan/strafe must yield real reflected camera velocity, not a frozen pose. */
public class MirrorTemporalHistoryTest {
    private static final float[] ID={1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};
    @Test public void cameraPanAndStrafeUsePreviousReflectedPose() {
        MirrorTemporalHistory history=new MirrorTemporalHistory();
        MirrorSurface mirror=new MirrorSurface(0,0,0,0,0,-1);
        MirrorTemporalHistory.Pose cold=history.advance(ID,ID,0,0,-3).reflect(mirror);
        assertFalse(cold.valid);
        float[] movedView=ID.clone();
        // Emulate an actual world camera pan (rotation) + lateral strafe.
        movedView[0]=0; movedView[4]=1; movedView[1]=-1; movedView[5]=0;
        movedView[12]=-.25f;
        MirrorTemporalHistory.Pose previous=history.advance(movedView,ID,0.25,0,-3).reflect(mirror);
        assertTrue(previous.valid);
        assertArrayEquals(cold.view,previous.view,0f);
        assertArrayEquals(cold.eye,previous.eye,0);
        float[] current=MirrorMatrices.multiply(movedView,ReflectionTransform.matrix(0,0,-1));
        assertFalse(java.util.Arrays.equals(current,previous.view));
        assertEquals(.25,mirror.plane().reflectPoint(.25,0,-3)[0],0);
        history.reset();
        assertFalse(history.advance(movedView,ID,.25,0,-3).reflect(mirror).valid);
    }

    @Test public void independentPlanesReflectSamePriorFrameCorrectly() {
        MirrorTemporalHistory history=new MirrorTemporalHistory();
        history.advance(ID,ID,2,1,-1);
        MirrorTemporalHistory.Frame prior=history.advance(ID,ID,3,1,-1);
        MirrorSurface wall=new MirrorSurface(0,0,0,1,0,0);
        MirrorSurface floor=new MirrorSurface(0,0,0,0,1,0);
        assertEquals(0,prior.reflect(wall).eye[0],0);
        assertEquals(1,prior.reflect(floor).eye[1],0);
    }
}
