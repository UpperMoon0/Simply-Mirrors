package com.nstut.simplymirrors.client;

import com.nstut.simplymirrors.math.MirrorSurface;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;

/** Isolated, reproducible compositor comparison; not an in-game FPS benchmark. */
public final class MirrorCompositeGlBenchmark {
    private static final int WIDTH=640, HEIGHT=640;
    private static final float[] MATRIX={1,0,0,0, 0,1,0,0, 0,0,.1f,0, 0,0,0,1};
    private MirrorCompositeGlBenchmark() { }

    public static void main(String[] args) {
        if (!glfwInit()) throw new AssertionError("GLFW initialization failed");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,3);
        long window=glfwCreateWindow(WIDTH,HEIGHT,"Simply Mirrors compositor benchmark",0,0);
        if (window==0) throw new AssertionError("OpenGL context unavailable");
        try {
            glfwMakeContextCurrent(window);
            GL.createCapabilities();
            glViewport(0,0,WIDTH,HEIGHT);
            glClearColor(0,0,0,1);
            System.out.println("Renderer: "+glGetString(GL_RENDERER)+" / "+glGetString(GL_VERSION));
            int texture=glGenTextures();
            glBindTexture(GL_TEXTURE_2D,texture);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
            ByteBuffer texels=BufferUtils.createByteBuffer(4);
            texels.put((byte)65).put((byte)175).put((byte)240).put((byte)255).flip();
            glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,1,1,0,GL_RGBA,GL_UNSIGNED_BYTE,texels);
            try(GlMirrorComposite composite=new GlMirrorComposite()) {
                for(int count : new int[]{64,256,1024,4096,8192}) run(composite,texture,count);
            } finally { glDeleteTextures(texture); }
            if(glGetError()!=GL_NO_ERROR)throw new AssertionError("OpenGL error after benchmark");
        } finally { glfwDestroyWindow(window); glfwTerminate(); }
    }

    private static void run(GlMirrorComposite composite,int texture,int count) {
        int columns=(int)Math.ceil(Math.sqrt(count));
        List<MirrorSurface> surfaces=new ArrayList<MirrorSurface>();
        for(int i=0;i<count;i++)surfaces.add(new MirrorSurface(i%columns,i/columns,0,0,0,-1));
        float[] vp=MATRIX.clone();
        vp[0]=2f/columns;vp[5]=2f/columns;
        double eyeX=columns/2.0,eyeY=columns/2.0,eyeZ=-3;
        // Identical source texture, vertex coordinates, depth and output for both paths.
        render(composite,texture,vp,eyeX,eyeY,eyeZ,surfaces,false);
        byte[] perBlock=readPixels();
        render(composite,texture,vp,eyeX,eyeY,eyeZ,surfaces,true);
        byte[] batched=readPixels();
        if(!Arrays.equals(perBlock,batched))
            throw new AssertionError("Batch changed pixels for "+count+" mirrors");
        double[] before=new double[4],after=new double[4];
        for(int trial=0;trial<4;trial++) {
            // Alternate first renderer so warm-up and CPU drift do not only favor batch.
            if((trial&1)==0) {
                before[trial]=render(composite,texture,vp,eyeX,eyeY,eyeZ,surfaces,false);
                after[trial]=render(composite,texture,vp,eyeX,eyeY,eyeZ,surfaces,true);
            } else {
                after[trial]=render(composite,texture,vp,eyeX,eyeY,eyeZ,surfaces,true);
                before[trial]=render(composite,texture,vp,eyeX,eyeY,eyeZ,surfaces,false);
            }
        }
        Arrays.sort(before);Arrays.sort(after);
        double base=(before[1]+before[2])/2,patch=(after[1]+after[2])/2;
        System.out.printf(java.util.Locale.ROOT,
            "MIRRORS=%d BASELINE_MS=%.3f BATCHED_MS=%.3f SPEEDUP=%.2fx PIXELS=IDENTICAL DRAWS=%d->1%n",
            count,base,patch,base/patch,count);
    }

    private static double render(GlMirrorComposite composite,int texture,float[] vp,
            double eyeX,double eyeY,double eyeZ,List<MirrorSurface> surfaces,boolean batch) {
        glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
        long start=System.nanoTime();
        if(batch)composite.drawBatch(texture,WIDTH,HEIGHT,vp,eyeX,eyeY,eyeZ,surfaces);
        else for(MirrorSurface cell:surfaces)composite.draw(texture,WIDTH,HEIGHT,vp,eyeX,eyeY,eyeZ,cell);
        glFinish();
        return (System.nanoTime()-start)/1_000_000.0;
    }

    private static byte[] readPixels() {
        ByteBuffer buffer=BufferUtils.createByteBuffer(WIDTH*HEIGHT*4);
        glReadPixels(0,0,WIDTH,HEIGHT,GL_RGBA,GL_UNSIGNED_BYTE,buffer);
        byte[] pixels=new byte[buffer.capacity()];
        buffer.get(pixels);
        return pixels;
    }
}
