package com.nstut.simplymirrors.math;

import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.Test;
import static org.junit.Assert.*;

/** Reproduces vanilla 1.20.1 Frustum.offsetToFullyIncludeCameraCube with a bounded loop. */
public class MirrorCullingTest {
    private static int fitCameraCube(Matrix4f view, Matrix4f projection) {
        Matrix4f matrix=projection.mul(view,new Matrix4f());
        FrustumIntersection intersection=new FrustumIntersection(matrix);
        Vector4f direction=matrix.transformTranspose(new Vector4f(0,0,1,0));
        double x=.5,y=91.62,z=2.25;
        double x0=Math.floor(x/8)*8,y0=Math.floor(y/8)*8,z0=Math.floor(z/8)*8;
        double x1=Math.ceil(x/8)*8,y1=Math.ceil(y/8)*8,z1=Math.ceil(z/8)*8;
        for(int step=0;step<512;step++) {
            if(intersection.intersectAab((float)(x0-x),(float)(y0-y),(float)(z0-z),
                (float)(x1-x),(float)(y1-y),(float)(z1-z))==FrustumIntersection.INSIDE)return step;
            x-=direction.x*4;y-=direction.y*4;z-=direction.z*4;
        }
        return -1;
    }

    @Test public void obliqueProjectionReproducesHangAtTwentyDegrees() {
        Matrix4f perspective=new Matrix4f().perspective((float)Math.toRadians(70),16f/9,.05f,256);
        Matrix4f reflectedView=new Matrix4f().rotateY((float)Math.toRadians(200)).scale(1,1,-1);
        float[] oblique=MirrorMatrices.oblique(perspective.get(new float[16]),reflectedView.get(new float[16]),
            0,0,-1,0,-1.62,-2.25,false);
        assertEquals("Vanilla's unbounded loop cannot fit this oblique frustum",-1,
            fitCameraCube(reflectedView,new Matrix4f().set(oblique)));
        assertTrue("Perspective culling must terminate",fitCameraCube(reflectedView,perspective)>=0);
    }

    @Test public void perspectiveCullingTerminatesAcrossReflectedViewingAnglesAndFovs() {
        for(int fov:new int[]{30,70,110})for(int pitch=-85;pitch<=85;pitch+=5)for(int yaw=-180;yaw<180;yaw+=5) {
            Matrix4f perspective=new Matrix4f().perspective((float)Math.toRadians(fov),16f/9,.05f,256);
            Matrix4f reflectedView=new Matrix4f().rotateX((float)Math.toRadians(pitch))
                .rotateY((float)Math.toRadians(yaw)).scale(1,1,-1);
            assertTrue("Camera fit failed at "+fov+"/"+pitch+"/"+yaw,fitCameraCube(reflectedView,perspective)>=0);
        }
    }
}
