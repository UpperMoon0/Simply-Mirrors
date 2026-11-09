package com.nstut.simplymirrors.math;

/**
 * Crops ONLY the reflected render's culling frustum to the conservative
 * union of visible mirror pixels. The actual projection, GPU viewport,
 * shader targets and frame history remain unchanged.
 *
 * Screen rectangle coordinates are OpenGL bottom-left pixels. A null or
 * unsafe rectangle returns the original projection without modification.
 */
public final class ReflectionFrustumCrop {
    private ReflectionFrustumCrop() {}

    public static float[] crop(float[] projection, int framebufferWidth,
                               int framebufferHeight, int[] rectangle) {
        if(projection==null || projection.length!=16)
            throw new IllegalArgumentException("Expected a 4x4 projection");
        if(rectangle==null || rectangle.length!=4 ||
                framebufferWidth<=0 || framebufferHeight<=0) return projection.clone();
        long x=rectangle[0],y=rectangle[1],w=rectangle[2],h=rectangle[3];
        if(x<0 || y<0 || w<=0 || h<=0 ||
                x+w>framebufferWidth || y+h>framebufferHeight)
            return projection.clone();
        // MirrorCoverage already pads by 96px for bloom/TAA; add 32px
        // for conservative section-AABB and entity culling.
        long x0=Math.max(0,x-32),y0=Math.max(0,y-32);
        long x1=Math.min(framebufferWidth,x+w+32),y1=Math.min(framebufferHeight,y+h+32);
        long bw=x1-x0,bh=y1-y0;
        if((double)bw*bh>=.85*framebufferWidth*framebufferHeight)
            return projection.clone();
        float sx=(float)framebufferWidth/bw;
        float sy=(float)framebufferHeight/bh;
        float tx=(float)(framebufferWidth-x0-x1)/bw;
        float ty=(float)(framebufferHeight-y0-y1)/bh;
        float[] crop={
            sx,0,0,0,
            0,sy,0,0,
            0,0,1,0,
            tx,ty,0,1
        };
        return MirrorMatrices.multiply(crop,projection);
    }
}
