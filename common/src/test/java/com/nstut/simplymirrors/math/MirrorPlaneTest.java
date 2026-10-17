package com.nstut.simplymirrors.math;

import org.junit.Test;
import java.util.Random;
import static org.junit.Assert.*;

public class MirrorPlaneTest {
    private static final double EPS = 1e-8;

    @Test public void reflectsAcrossOffsetPlane() {
        assertArrayEquals(new double[] {2, 5, -1}, new MirrorPlane(0, 0, 3, 0, 0, 1).reflectPoint(2, 5, 7), EPS);
    }
    @Test public void directionsAreIndependentOfPlaneLocation() {
        assertArrayEquals(new double[] {-1, 2, 3}, new MirrorPlane(100, 50, -20, 1, 0, 0).reflectDirection(1, 2, 3), EPS);
    }
    @Test public void normalMagnitudeDoesNotChangeReflection() {
        assertArrayEquals(new MirrorPlane(2, 3, 4, 1, 2, 3).reflectPoint(7, 8, 9),
                new MirrorPlane(2, 3, 4, 10, 20, 30).reflectPoint(7, 8, 9), EPS);
    }
    @Test public void reversingNormalPreservesReflectionButChangesVisibleSide() {
        MirrorPlane a = new MirrorPlane(0, 0, 0, 0, 0, 1);
        MirrorPlane b = new MirrorPlane(0, 0, 0, 0, 0, -1);
        assertArrayEquals(a.reflectPoint(2, 3, 4), b.reflectPoint(2, 3, 4), EPS);
        assertTrue(a.isFrontFacing(0, 0, 4, .001));
        assertFalse(b.isFrontFacing(0, 0, 4, .001));
    }
    @Test public void planePointsStayFixed() {
        assertArrayEquals(new double[] {2, 3, 4}, new MirrorPlane(2, 3, 4, 1, 2, 3).reflectPoint(2, 3, 4), EPS);
    }
    @Test public void reflectionIsAnInvolutionAndPreservesDistances() {
        Random random = new Random(173);
        for (int i = 0; i < 1000; i++) {
            MirrorPlane plane = new MirrorPlane(random.nextDouble(), random.nextDouble(), random.nextDouble(),
                    random.nextDouble() + .1, random.nextDouble() + .1, random.nextDouble() + .1);
            double[] p = {random.nextDouble() * 100, random.nextDouble() * 100, random.nextDouble() * 100};
            double[] r = plane.reflectPoint(p[0], p[1], p[2]);
            assertArrayEquals(p, plane.reflectPoint(r[0], r[1], r[2]), EPS);
            assertEquals(-plane.signedDistance(p[0], p[1], p[2]), plane.signedDistance(r[0], r[1], r[2]), EPS);
            double[] d = plane.reflectDirection(p[0], p[1], p[2]);
            assertEquals(length(p), length(d), EPS);
        }
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsZeroNormal() { new MirrorPlane(0, 0, 0, 0, 0, 0); }
    @Test(expected = IllegalArgumentException.class) public void rejectsNaN() { new MirrorPlane(Double.NaN, 0, 0, 1, 0, 0); }
    @Test(expected = IllegalArgumentException.class) public void rejectsInfiniteNormal() { new MirrorPlane(0, 0, 0, Double.POSITIVE_INFINITY, 0, 0); }
    @Test(expected = IllegalArgumentException.class) public void rejectsNegativeTolerance() { new MirrorPlane(0, 0, 0, 1, 0, 0).isFrontFacing(1, 0, 0, -1); }
    @Test public void surfaceIsNotFrontFacing() { assertFalse(new MirrorPlane(0, 0, 0, 1, 0, 0).isFrontFacing(0, 0, 0, 0)); }
    private static double length(double[] a) { return Math.hypot(Math.hypot(a[0], a[1]), a[2]); }
}
