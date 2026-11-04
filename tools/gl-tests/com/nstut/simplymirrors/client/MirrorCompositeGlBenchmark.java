package com.nstut.simplymirrors.client;

import com.nstut.simplymirrors.math.MirrorSurface;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Collections;
import java.util.HashSet;
import java.util.Random;
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
                cacheInvalidationParity(composite,texture);
                cameraPanCase(texture,"dense",4096,1);
                cameraPanCase(texture,"fragmented",6144,3);
                stressCase(texture,"checkerboard",8192,1);
                stressCase(texture,"scattered",6144,2);
                stressCase(texture,"multi-plane",6144,6);
                stressCase(texture,"holed",4096,3);
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

    /** A tile removed and replaced in the same plane must invalidate the mesh cache. */
    private static void cacheInvalidationParity(GlMirrorComposite composite,int texture) {
        List<MirrorSurface> original=new ArrayList<MirrorSurface>();
        for(int i=0;i<64;i++)original.add(new MirrorSurface(i%8,i/8,0,0,0,-1));
        float[] vp=MATRIX.clone();vp[0]=.25f;vp[5]=.25f;
        render(composite,texture,vp,4,4,-3,original,2);
        List<MirrorSurface> changed=new ArrayList<MirrorSurface>(original);
        changed.set(25,new MirrorSurface(10,10,0,0,0,-1));
        render(composite,texture,vp,4,4,-3,changed,1);
        byte[] baseline=readPixels();
        render(composite,texture,vp,4,4,-3,changed,2);
        if(!Arrays.equals(baseline,readPixels()))
            throw new AssertionError("Same-size mirror edit reused stale cached geometry");
        System.out.println("PASS: exact cache invalidation after changing one tile");
    }

    /**
     * Move the camera across static geometry and distance-sort it each frame.
     * Every 20 frames rebuild all MirrorSurface objects as find() does.
     * At frames 32 and 48 edit/restore one tile without changing face count.
     * The benchmark reports exact mesh-cache hits/rebuilds and moving-frame p95.
     */
    private static void cameraPanCase(int texture,String layout,int totalFaces,int planes) {
        final int frames=72,perPlane=totalFaces/planes;
        int columns=(int)Math.ceil(Math.sqrt(perPlane*(layout.equals("fragmented")?2.0:1.0)));
        List<List<MirrorSurface>> originals=new ArrayList<List<MirrorSurface>>();
        for(int p=0;p<planes;p++) {
            List<MirrorSurface> faces=new ArrayList<MirrorSurface>(perPlane);
            for(int i=0;i<perPlane;i++) {
                int index=layout.equals("fragmented")?i*2:i;
                faces.add(new MirrorSurface(index%columns,index/columns,p*3,0,0,-1));
            }
            originals.add(faces);
        }
        float[] vp=MATRIX.clone();vp[0]=2f/columns;vp[5]=2f/columns;
        List<List<MirrorSurface>> active=new ArrayList<List<MirrorSurface>>();
        double[] before=new double[frames],after=new double[frames];
        int comparisons=0;
        try(GlMirrorComposite composite=new GlMirrorComposite()) {
            for(int frame=0;frame<frames;frame++) {
                // Real camera movement changes the distance order within a plane.
                double eyeX=columns/2.0+Math.sin(frame*.16)*columns*.27;
                double eyeY=columns/2.0+Math.cos(frame*.13)*columns*.21;
                double eyeZ=-3+Math.sin(frame*.11)*.5;
                if(frame%20==0||frame==32||frame==48) {
                    active.clear();
                    for(int p=0;p<planes;p++) {
                        List<MirrorSurface> refreshed=new ArrayList<MirrorSurface>(perPlane);
                        for(MirrorSurface s:originals.get(p))
                            refreshed.add(new MirrorSurface(s.x,s.y,s.z,s.nx,s.ny,s.nz));
                        if(p==0&&frame>=32&&frame<48) {
                            MirrorSurface removed=refreshed.get(50);
                            refreshed.set(50,new MirrorSurface(-10,removed.y,removed.z,0,0,-1));
                        }
                        // If the original list was refreshed at frame 40, the changed tile
                        // remains until frame 48, and a new geometry build is unnecessary.
                        active.add(refreshed);
                    }
                }
                // Keep the edited tile through the normal frame-40 rescan.
                for(List<MirrorSurface> surfaces:active)
                    Collections.sort(surfaces,java.util.Comparator.comparingDouble(s -> {
                        double x=s.cx-eyeX,y=s.cy-eyeY,z=s.cz-eyeZ;
                        return x*x+y*y+z*z;
                    }));
                before[frame]=renderMany(composite,texture,vp,eyeX,eyeY,eyeZ,active,false);
                byte[] baseline=(frame==0||frame==20||frame==32||frame==40||frame==48||frame==71)
                        ?readPixels():null;
                after[frame]=renderMany(composite,texture,vp,eyeX,eyeY,eyeZ,active,true);
                if(baseline!=null) {
                    if(!Arrays.equals(baseline,readPixels()))
                        throw new AssertionError("Camera pan/edit changed pixels: "+layout+" frame="+frame);
                    comparisons++;
                }
            }
            long expectedBuilds=planes+2;
            long expectedHits=(long)frames*planes-expectedBuilds;
            if(composite.meshCacheBuilds()!=expectedBuilds||composite.meshCacheHits()!=expectedHits)
                throw new AssertionError("Camera motion caused redundant mesh rebuilds: "+
                        "layout="+layout+" builds="+composite.meshCacheBuilds()+"/"+expectedBuilds+
                        " hits="+composite.meshCacheHits()+"/"+expectedHits);
            Arrays.sort(before);Arrays.sort(after);
            int p95=(int)Math.ceil(frames*.95)-1;
            System.out.printf(java.util.Locale.ROOT,
                    "CAMERA_PAN=%s FACES=%d PLANES=%d FRAMES=%d "+
                    "MESH_BUILDS=%d CACHE_HITS=%d EXPECTED_BUILDS=%d "+
                    "BATCH_P95_MS=%.3f MERGED_P95_MS=%.3f "+
                    "BATCH_MEDIAN_MS=%.3f MERGED_MEDIAN_MS=%.3f "+
                    "PARITY_CAPTURES=%d PIXELS=IDENTICAL%n",
                    layout,totalFaces,planes,frames,composite.meshCacheBuilds(),
                    composite.meshCacheHits(),expectedBuilds,before[p95],after[p95],
                    (before[35]+before[36])/2,(after[35]+after[36])/2,comparisons);
        }
    }

    /** Frame-time tail and direct staging-allocation rate for shapes dense walls miss. */
    private static void stressCase(int texture,String layout,int totalFaces,int planeCount) {
        List<List<MirrorSurface>> planes=new ArrayList<List<MirrorSurface>>();
        int perPlane=totalFaces/planeCount;
        int columns=(int)Math.ceil(Math.sqrt(perPlane*(layout.equals("checkerboard")?2:1.25)));
        Random random=new Random(4751);
        for(int plane=0;plane<planeCount;plane++) {
            List<MirrorSurface> faces=new ArrayList<MirrorSurface>(perPlane);
            HashSet<Long> occupied=new HashSet<Long>();
            for(int i=0;i<perPlane;) {
                int x,y;
                if(layout.equals("checkerboard")) {
                    int index=i*2;
                    x=index%columns;y=index/columns;
                } else if(layout.equals("scattered")) {
                    x=random.nextInt(columns);y=random.nextInt(columns);
                } else if(layout.equals("holed")) {
                    int index=i+((i/Math.max(1,columns-4))*4);
                    x=index%columns;y=index/columns;
                } else {
                    x=i%columns;y=i/columns;
                }
                if(x>=columns||y>=columns*2)break;
                long position=(((long)x)<<32)|(y&0xffffffffL);
                if(!occupied.add(position))continue;
                // Different plane offsets avoid accidentally merging independent reflections.
                faces.add(new MirrorSurface(x,y,plane*3,0,0,-1));i++;
            }
            planes.add(faces);
        }
        float[] vp=MATRIX.clone();vp[0]=2f/columns;vp[5]=2f/columns;
        double eyeX=columns/2.0,eyeY=columns/2.0,eyeZ=-3;
        try(GlMirrorComposite composite=new GlMirrorComposite()) {
            renderMany(composite,texture,vp,eyeX,eyeY,eyeZ,planes,false);
            byte[] baselinePixels=readPixels();
            renderMany(composite,texture,vp,eyeX,eyeY,eyeZ,planes,true);
            if(!Arrays.equals(baselinePixels,readPixels()))
                throw new AssertionError("Stress layout changed visible pixels: "+layout);
            // Warm up GPU, JIT, and the reusable buffer to full scene capacity.
            for(int i=0;i<3;i++)renderMany(composite,texture,vp,eyeX,eyeY,eyeZ,planes,true);
            long before=composite.nativeVertexAllocationBytes();
            final int samples=20;
            double[] after=new double[samples],prior=new double[samples];
            for(int i=0;i<samples;i++) {
                after[i]=renderMany(composite,texture,vp,eyeX,eyeY,eyeZ,planes,true);
                prior[i]=renderMany(composite,texture,vp,eyeX,eyeY,eyeZ,planes,false);
            }
            long nativeTotal=composite.nativeVertexAllocationBytes()-before;
            // Baseline buffer allocations above are known exactly: each drawBatch
            // creates one direct buffer of faces*24 floats per group each frame.
            int actualFaces=0;
            for(List<MirrorSurface> plane:planes)actualFaces+=plane.size();
            long originalNative=(long)actualFaces*24*Float.BYTES*samples;
            long mergedNative=nativeTotal-originalNative;
            if(mergedNative!=0)throw new AssertionError("Steady-state merged compositor allocated direct buffers: "+mergedNative);
            Arrays.sort(after);Arrays.sort(prior);
            int p95=(int)Math.ceil(samples*.95)-1;
            System.out.printf(java.util.Locale.ROOT,
                "STRESS=%s FACES=%d PLANES=%d BATCH_P95_MS=%.3f MERGED_P95_MS=%.3f "+
                "BATCH_MEDIAN_MS=%.3f MERGED_MEDIAN_MS=%.3f NEW_DIRECT_BYTES_PER_FRAME=%d "+
                "BASELINE_DIRECT_BYTES_PER_FRAME=%d PIXELS=IDENTICAL%n",
                layout,actualFaces,planeCount,prior[p95],after[p95],
                (prior[9]+prior[10])/2,(after[9]+after[10])/2,mergedNative/samples,
                originalNative/samples);
        }
    }
    private static double renderMany(GlMirrorComposite composite,int texture,float[] vp,
            double eyeX,double eyeY,double eyeZ,List<List<MirrorSurface>> planes,boolean merged) {
        glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
        long start=System.nanoTime();
        for(List<MirrorSurface> plane:planes) {
            if(merged)composite.drawMergedBatch(texture,WIDTH,HEIGHT,vp,eyeX,eyeY,eyeZ,plane);
            else composite.drawBatch(texture,WIDTH,HEIGHT,vp,eyeX,eyeY,eyeZ,plane);
        }
        glFinish();
        return (System.nanoTime()-start)/1_000_000.0;
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
