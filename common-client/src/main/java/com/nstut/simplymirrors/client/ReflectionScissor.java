package com.nstut.simplymirrors.client;

import com.nstut.simplymirrors.math.MirrorMatrices;
import com.nstut.simplymirrors.math.MirrorSurface;
import org.lwjgl.BufferUtils;
import java.nio.IntBuffer;
import java.util.List;
import static org.lwjgl.opengl.GL33.*;

/**
 * Full-resolution, pixel-exact planar reflection coverage culling.
 *
 * Drawing a 150-pixel mirror normally executes expensive Oculus/Complementary
 * fragments at every pixel of the whole screen. The reflected camera's raster
 * positions have exactly the same viewport coordinates as the original camera
 * over the planar face. Limit shader draws to the conservative union of those
 * faces, with broad padding for TAA, bloom, interpolation and postprocessing.
 *
 * Geometry, shadow-map rasterization, and final mirror compositing retain
 * their original resolution. No texture downscaling or frame skipping.
 */
public final class ReflectionScissor implements AutoCloseable {
    private static final double FULL_SCREEN_THRESHOLD = 0.85;
    private static final boolean ENABLED =
        !"false".equalsIgnoreCase(System.getProperty("simplymirrors.coverageScissor", "true"));
    private static final ReflectionScissor NOOP = new ReflectionScissor();
    private final boolean active;
    private final boolean oldEnabled;
    private final int oldX, oldY, oldWidth, oldHeight;
    private static int clipped, skipped;

    private ReflectionScissor() {
        active=false;oldEnabled=false;oldX=oldY=oldWidth=oldHeight=0;
    }

    private ReflectionScissor(int x, int y, int width, int height) {
        active=true;
        oldEnabled=glIsEnabled(GL_SCISSOR_TEST);
        IntBuffer previous=BufferUtils.createIntBuffer(4);
        glGetIntegerv(GL_SCISSOR_BOX, previous);
        oldX=previous.get(0);oldY=previous.get(1);
        oldWidth=previous.get(2);oldHeight=previous.get(3);
        glScissor(x,y,width,height);
        glEnable(GL_SCISSOR_TEST);
    }

    public static ReflectionScissor begin(float[] viewProjection,
                                          double eyeX,double eyeY,double eyeZ,
                                          int width,int height,List<MirrorSurface> faces) {
        if(!ENABLED || faces.isEmpty() || width<=0 || height<=0) return NOOP;
        int[] bounds=calculate(viewProjection,eyeX,eyeY,eyeZ,width,height,faces);
        if(bounds==null) {
            skipped++;
            return NOOP;
        }
        clipped++;
        if(Boolean.getBoolean("simplymirrors.gpuProfile") && (clipped+skipped)%180==0)
            System.out.println("SIMPLY_MIRRORS_COVERAGE_SCISSOR clipped="+clipped+
                " fallback="+skipped+" rect="+bounds[0]+","+bounds[1]+","+bounds[2]+","+bounds[3]+
                " full="+width+"x"+height);
        return new ReflectionScissor(bounds[0],bounds[1],bounds[2],bounds[3]);
    }

    /**
     * Return null to render the full screen when a face straddles the near
     * plane, has an invalid projection, or covers most of the viewport.
     * This is a correctness fallback, never a reason to omit a mirror.
     */
    static int[] calculate(float[] vp,double eyeX,double eyeY,double eyeZ,
                           int width,int height,List<MirrorSurface> faces) {
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
                if(!Double.isFinite(w)||w<=.001 ||
                    !Double.isFinite(clip[0])||!Double.isFinite(clip[1])||
                    !Double.isFinite(clip[2])||
                    clip[2]<-w || clip[2]>w) return null;
                double x=(clip[0]/w*.5+.5)*width;
                double y=(clip[1]/w*.5+.5)*height;
                if(!Double.isFinite(x)||!Double.isFinite(y)) return null;
                minX=Math.min(minX,x);minY=Math.min(minY,y);
                maxX=Math.max(maxX,x);maxY=Math.max(maxY,y);
            }
        }
        // Padding scales with screen resolution so large-frame postprocess
        // kernels keep their neighboring samples fully rendered.
        int pad=Math.max(96,Math.max(width,height)/16);
        int x0=Math.max(0,(int)Math.floor(minX)-pad);
        int y0=Math.max(0,(int)Math.floor(minY)-pad);
        int x1=Math.min(width,(int)Math.ceil(maxX)+pad);
        int y1=Math.min(height,(int)Math.ceil(maxY)+pad);
        int areaWidth=x1-x0,areaHeight=y1-y0;
        if(areaWidth<=0||areaHeight<=0 ||
            (double)areaWidth*areaHeight >= FULL_SCREEN_THRESHOLD*width*height) return null;
        return new int[]{x0,y0,areaWidth,areaHeight};
    }

    @Override public void close() {
        if(!active)return;
        glScissor(oldX,oldY,oldWidth,oldHeight);
        if(oldEnabled)glEnable(GL_SCISSOR_TEST);
        else glDisable(GL_SCISSOR_TEST);
    }
}
