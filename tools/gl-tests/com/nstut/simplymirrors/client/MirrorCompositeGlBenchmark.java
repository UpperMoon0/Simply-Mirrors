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
            // Spatially varying full-color texture catches shifted sampling and seams.
            ByteBuffer texels=BufferUtils.createByteBuffer(32*32*4);
            for(int y=0;y<32;y++)for(int x=0;x<32;x++) {
                texels.put((byte)(x*7+13)).put((byte)(y*7+11)).put((byte)((x^y)*7+7)).put((byte)255);
            }
            texels.flip();
            glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,32,32,0,GL_RGBA,GL_UNSIGNED_BYTE,texels);
            try(GlMirrorComposite composite=new GlMirrorComposite()) {
                for(int count : new int[]{64,256,1024,4096,8192}) run(composite,texture,count);
                for(int[] normal : new int[][]{{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}})
                    runFaceParity(composite,texture,normal);
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
        render(composite,texture,vp,eyeX,eyeY,eyeZ,surfaces,0);
        byte[] perBlock=readPixels();
        render(composite,texture,vp,eyeX,eyeY,eyeZ,surfaces,1);
        byte[] batched=readPixels();
        if(!Arrays.equals(perBlock,batched))
            throw new AssertionError("Batch changed pixels for "+count+" mirrors");
        render(composite,texture,vp,eyeX,eyeY,eyeZ,surfaces,2);
        byte[] merged=readPixels();
        if(!Arrays.equals(batched,merged))
            throw new AssertionError("Greedy mesh changed pixels for "+count+" mirrors");
        double[] before=new double[4],after=new double[4],newest=new double[4];
        for(int trial=0;trial<4;trial++) {
            // Alternate first renderer so warm-up and CPU drift do not only favor batch.
            if((trial&1)==0) {
                before[trial]=render(composite,texture,vp,eyeX,eyeY,eyeZ,surfaces,0);
                after[trial]=render(composite,texture,vp,eyeX,eyeY,eyeZ,surfaces,1);
                newest[trial]=render(composite,texture,vp,eyeX,eyeY,eyeZ,surfaces,2);
            } else {
                newest[trial]=render(composite,texture,vp,eyeX,eyeY,eyeZ,surfaces,2);
                after[trial]=render(composite,texture,vp,eyeX,eyeY,eyeZ,surfaces,1);
                before[trial]=render(composite,texture,vp,eyeX,eyeY,eyeZ,surfaces,0);
            }
        }
        Arrays.sort(before);Arrays.sort(after);Arrays.sort(newest);
        double base=(before[1]+before[2])/2,patch=(after[1]+after[2])/2,mergedMs=(newest[1]+newest[2])/2;
        int rectangles=com.nstut.simplymirrors.math.MirrorPatch.merge(surfaces).size();
        System.out.printf(java.util.Locale.ROOT,
            "MIRRORS=%d BASELINE_MS=%.3f BATCHED_MS=%.3f MERGED_MS=%.3f VS_BATCHED=%.2fx VS_ORIGINAL=%.2fx PIXELS=IDENTICAL QUADS=%d->%d%n",
            count,base,patch,mergedMs,patch/mergedMs,base/mergedMs,count,rectangles);
    }

    private static void runFaceParity(GlMirrorComposite composite,int texture,int[] normal) {
        final int span=20;
        List<MirrorSurface> cells=new ArrayList<MirrorSurface>();
        for(int v=0;v<span;v++)for(int u=0;u<span;u++) {
            if(u>=5&&u<15&&v>=5&&v<15)continue; // A rectangular hole must stay empty.
            int x=normal[0]!=0?0:u;
            int y=normal[1]!=0?0:v;
            int z=normal[0]!=0?u:normal[1]!=0?v:0;
            cells.add(new MirrorSurface(x,y,z,normal[0],normal[1],normal[2]));
        }
        float[] view=new float[16];
        view[15]=1; view[10]=.01f;
        if(normal[0]!=0) {view[8]=2f/span;view[5]=2f/span;}
        else if(normal[1]!=0) {view[0]=2f/span;view[9]=2f/span;}
        else {view[0]=2f/span;view[5]=2f/span;}
        double eyeX=normal[0]!=0?3:span/2.0;
        double eyeY=normal[1]!=0?3:span/2.0;
        double eyeZ=normal[2]!=0?3:span/2.0;
        render(composite,texture,view,eyeX,eyeY,eyeZ,cells,1);
        byte[] original=readPixels();
        render(composite,texture,view,eyeX,eyeY,eyeZ,cells,2);
        if(!Arrays.equals(original,readPixels()))
            throw new AssertionError("Irregular hole changed pixels for normal "+Arrays.toString(normal));
        System.out.println("PASS: exact RGBA parity with hole, normal="+Arrays.toString(normal));
    }

    private static double render(GlMirrorComposite composite,int texture,float[] vp,
            double eyeX,double eyeY,double eyeZ,List<MirrorSurface> surfaces,int mode) {
        glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
        long start=System.nanoTime();
        if(mode==2)composite.drawMergedBatch(texture,WIDTH,HEIGHT,vp,eyeX,eyeY,eyeZ,surfaces);
        else if(mode==1)composite.drawBatch(texture,WIDTH,HEIGHT,vp,eyeX,eyeY,eyeZ,surfaces);
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
