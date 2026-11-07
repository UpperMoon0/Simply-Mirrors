package com.nstut.simplymirrors.client;

import com.nstut.simplymirrors.math.MirrorCoverage;
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

    /** Pure projection bounds are unit-tested in the common math module. */
    static int[] calculate(float[] vp,double eyeX,double eyeY,double eyeZ,
                           int width,int height,List<MirrorSurface> faces) {
        return MirrorCoverage.scissor(vp,eyeX,eyeY,eyeZ,width,height,faces);
    }

    @Override public void close() {
        if(!active)return;
        glScissor(oldX,oldY,oldWidth,oldHeight);
        if(oldEnabled)glEnable(GL_SCISSOR_TEST);
        else glDisable(GL_SCISSOR_TEST);
    }
}
