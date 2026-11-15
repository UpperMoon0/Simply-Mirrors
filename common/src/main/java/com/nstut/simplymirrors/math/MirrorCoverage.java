package com.nstut.simplymirrors.math;

import java.util.List;

/**
 * Conservatively bounds every visible planar mirror face in full-resolution
 * framebuffer pixels. No OpenGL context is required by this pure helper.
 *
 * A camera clip-plane crossing, near-plane crossing, or very large mirror
 * returns null: the renderer must fall back to unrestricted drawing rather
 * than risk omitting any visible reflected pixels.
 */
public final class MirrorCoverage {
    private static final double FULL_SCREEN_THRESHOLD=0.85;

    private MirrorCoverage() { }

    /**
     * @return {left,bottom,width,height} in GL framebuffer coordinates,
     *         or null if the safe/correct choice is the full viewport.
     */
    public static int[] scissor(float[] vp,double eyeX,double eyeY,double eyeZ,
                                int width,int height,List<MirrorSurface> faces) {
        if(faces.isEmpty()||width<=0||height<=0)return null;
        double minX=Double.POSITIVE_INFINITY,minY=Double.POSITIVE_INFINITY;
        double maxX=Double.NEGATIVE_INFINITY,maxY=Double.NEGATIVE_INFINITY;
        double[] corners=new double[12];
        for(MirrorSurface surface:faces) {
            surface.writeCorners(corners);
            for(int i=0;i<4;i++) {
                int j=i*3;
                double[] clip=MirrorMatrices.transform(vp,
                    corners[j]-eyeX,corners[j+1]-eyeY,corners[j+2]-eyeZ,1);
                double w=clip[3];
                // Any partial near/far-plane intersection can alter the
                // projected polygon. Do not guess its bounding rectangle.
                if(!Double.isFinite(w)||w<=.001 ||
                    !Double.isFinite(clip[0])||!Double.isFinite(clip[1])||
                    !Double.isFinite(clip[2])||
                    clip[2]<-w || clip[2]>w) return null;
                double x=(clip[0]/w*.5+.5)*width;
                double y=(clip[1]/w*.5+.5)*height;
                if(!Double.isFinite(x)||!Double.isFinite(y))return null;
                minX=Math.min(minX,x);minY=Math.min(minY,y);
                maxX=Math.max(maxX,x);maxY=Math.max(maxY,y);
            }
        }
        // The padding protects filtering and shader effects whose samples
        // reach outside the visible mirror quad (bloom, TAA, motion blur).
        int pad=Math.max(96,Math.max(width,height)/16);
        int x0=Math.max(0,(int)Math.floor(minX)-pad);
        int y0=Math.max(0,(int)Math.floor(minY)-pad);
        int x1=Math.min(width,(int)Math.ceil(maxX)+pad);
        int y1=Math.min(height,(int)Math.ceil(maxY)+pad);
        int w=x1-x0,h=y1-y0;
        if(w<=0||h<=0||(double)w*h>=FULL_SCREEN_THRESHOLD*width*height)
            return null;
        return new int[]{x0,y0,w,h};
    }
}
