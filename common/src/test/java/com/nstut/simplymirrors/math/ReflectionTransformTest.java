package com.nstut.simplymirrors.math;

import org.junit.Test;
import static org.junit.Assert.*;

public class ReflectionTransformTest {
    @Test public void axialPlanesReverseOnlyTheirNormalComponent() {
        for(int axis=0;axis<3;axis++)for(int sign:new int[]{-1,1}) {
            double[] n=new double[3];n[axis]=sign;
            double[] expected={2,3,4};expected[axis]=-expected[axis];
            assertArrayEquals(expected,ReflectionTransform.reflectVector(2,3,4,n[0],n[1],n[2]),1e-12);
        }
    }
    @Test public void obliquePlaneHasExpectedVectorAndMatrix() {
        assertArrayEquals(new double[]{-2.32,-2.76,4},ReflectionTransform.reflectVector(2,3,4,.6,.8,0),1e-12);
        assertArrayEquals(new float[]{.28f,-.96f,0,0,-.96f,-.28f,0,0,0,0,1,0,0,0,0,1},
            ReflectionTransform.matrix(.6,.8,0),1e-6f);
    }
}
