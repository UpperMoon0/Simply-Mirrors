package com.nstut.simplymirrors.client;

import com.nstut.simplymirrors.math.MirrorMatrices;
import com.nstut.simplymirrors.math.MirrorSurface;
import com.nstut.simplymirrors.math.MirrorGeometry;
import com.nstut.simplymirrors.math.MirrorPatch;
import com.nstut.simplymirrors.math.MirrorFaceSet;
import org.lwjgl.BufferUtils;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import static org.lwjgl.opengl.GL33.*;

/** Projectively samples the reflection at its screen-space location, retaining the world's depth test. */
public final class GlMirrorComposite implements AutoCloseable {
    private int program, vao, vbo;
    /** Persistent direct staging allocation: grow only when an unusually fragmented scene needs it. */
    private FloatBuffer staging;
    private final double[] corners=new double[12];
    private long nativeAllocationBytes;
    /** Per-plane LRU: compare exact face coordinates, independent of camera order. */
    private final Map<String,CachedMesh> meshes=new LinkedHashMap<String,CachedMesh>(16,.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String,CachedMesh> eldest) {
            return size()>12;
        }
    };
    private static final class CachedMesh {
        final MirrorFaceSet faces;
        final List<MirrorPatch> patches;
        CachedMesh(List<MirrorSurface> surfaces,List<MirrorPatch> patches) {
            this.faces=new MirrorFaceSet(surfaces);
            this.patches=patches;
        }
        boolean matches(List<MirrorSurface> surfaces) { return faces.matches(surfaces); }
    }
    private long meshCacheHits,meshCacheBuilds;
    public long meshCacheHits() { return meshCacheHits; }
    public long meshCacheBuilds() { return meshCacheBuilds; }
    public long nativeVertexAllocationBytes() { return nativeAllocationBytes; }
    private FloatBuffer vertices(int floats,boolean persistent) {
        if(!persistent) {
            nativeAllocationBytes+=(long)floats*Float.BYTES;
            return BufferUtils.createFloatBuffer(floats);
        }
        if(staging==null||staging.capacity()<floats) {
            int capacity=staging==null?128:staging.capacity();
            while(capacity<floats)capacity=Math.max(floats,capacity*2);
            staging=BufferUtils.createFloatBuffer(capacity);
            nativeAllocationBytes+=(long)capacity*Float.BYTES;
        }
        staging.clear();
        return staging;
    }
    private static final int[] TRIANGLE_INDICES = {0,1,2,0,2,3};
    /** Compatibility path retained for the baseline benchmark. */
    public void draw(int texture,int width,int height,float[] viewProjection,double eyeX,double eyeY,double eyeZ,MirrorSurface surface) {
        drawBatch(texture,width,height,viewProjection,eyeX,eyeY,eyeZ,Collections.singletonList(surface));
    }
    /** Compatibility path: one draw per plane, with an individual quad per face. */
    public void drawBatch(int texture,int width,int height,float[] viewProjection,double eyeX,double eyeY,double eyeZ,List<MirrorSurface> surfaces) {
        drawGeometry(texture,width,height,viewProjection,eyeX,eyeY,eyeZ,surfaces,false);
    }
    /** Merge adjacent unit faces into exact rectangle coverage; retain the full-resolution texture. */
    public void drawMergedBatch(int texture,int width,int height,float[] viewProjection,double eyeX,double eyeY,double eyeZ,List<MirrorSurface> surfaces) {
        drawMergedBatch(texture,width,height,viewProjection,eyeX,eyeY,eyeZ,surfaces,1.0f,1.0f);
    }
    /** Preserve native shader buffers while sampling a reduced viewport. */
    public void drawMergedBatch(int texture,int width,int height,float[] viewProjection,double eyeX,double eyeY,double eyeZ,List<MirrorSurface> surfaces,float sampleScaleX,float sampleScaleY) {
        if(surfaces.size()<16) {
            drawGeometry(texture,width,height,viewProjection,eyeX,eyeY,eyeZ,surfaces,true,sampleScaleX,sampleScaleY);
        } else {
            String key=surfaces.get(0).planeKey();
            CachedMesh cached=meshes.get(key);
            if(cached==null||!cached.matches(surfaces)) {
                cached=new CachedMesh(surfaces,MirrorPatch.merge(surfaces));
                meshes.put(key,cached);
                meshCacheBuilds++;
            } else {
                meshCacheHits++;
            }
            drawGeometry(texture,width,height,viewProjection,eyeX,eyeY,eyeZ,cached.patches,true,sampleScaleX,sampleScaleY);
        }
    }
    private void drawGeometry(int texture,int width,int height,float[] viewProjection,double eyeX,double eyeY,double eyeZ,List<? extends MirrorGeometry> surfaces,boolean persistent) {
        drawGeometry(texture,width,height,viewProjection,eyeX,eyeY,eyeZ,surfaces,persistent,1.0f,1.0f);
    }
    private void drawGeometry(int texture,int width,int height,float[] viewProjection,double eyeX,double eyeY,double eyeZ,List<? extends MirrorGeometry> surfaces,boolean persistent,float sampleScaleX,float sampleScaleY) {
        if(surfaces.isEmpty())return;
        if(program==0) initialize();
        int oldProgram=glGetInteger(GL_CURRENT_PROGRAM),oldVao=glGetInteger(GL_VERTEX_ARRAY_BINDING),oldBuffer=glGetInteger(GL_ARRAY_BUFFER_BINDING);
        int oldActive=glGetInteger(GL_ACTIVE_TEXTURE);glActiveTexture(GL_TEXTURE0);int oldTexture=glGetInteger(GL_TEXTURE_BINDING_2D);
        boolean samplers=org.lwjgl.opengl.GL.getCapabilities().OpenGL33||org.lwjgl.opengl.GL.getCapabilities().GL_ARB_sampler_objects;
        int oldSampler=samplers?glGetInteger(GL_SAMPLER_BINDING):0;
        if(samplers)glBindSampler(0,0);
        boolean depth=glIsEnabled(GL_DEPTH_TEST),cull=glIsEnabled(GL_CULL_FACE),blend=glIsEnabled(GL_BLEND);
        boolean depthMask=glGetBoolean(GL_DEPTH_WRITEMASK);int depthFunc=glGetInteger(GL_DEPTH_FUNC);
        try {
            FloatBuffer vertices=vertices(Math.multiplyExact(surfaces.size(),24),persistent);
            for(MirrorGeometry surface:surfaces) {
                surface.writeCorners(corners);
                for(int index:TRIANGLE_INDICES) {
                    int at=index*3;
                    double x=corners[at]-eyeX,y=corners[at+1]-eyeY,z=corners[at+2]-eyeZ;
                    double cx=viewProjection[0]*x+viewProjection[4]*y+viewProjection[8]*z+viewProjection[12];
                    double cy=viewProjection[1]*x+viewProjection[5]*y+viewProjection[9]*z+viewProjection[13];
                    double cz=viewProjection[2]*x+viewProjection[6]*y+viewProjection[10]*z+viewProjection[14];
                    double cw=viewProjection[3]*x+viewProjection[7]*y+viewProjection[11]*z+viewProjection[15];
                    vertices.put((float)cx).put((float)cy).put((float)(cz-cw*.00001)).put((float)cw);
                }
            }
            vertices.flip();glEnable(GL_DEPTH_TEST);glDepthFunc(GL_LEQUAL);glDepthMask(false);glDisable(GL_CULL_FACE);glDisable(GL_BLEND);
            glUseProgram(program);glUniform1i(glGetUniformLocation(program,"Reflection"),0);glUniform2f(glGetUniformLocation(program,"Size"),width,height);glUniform2f(glGetUniformLocation(program,"SampleScale"),sampleScaleX,sampleScaleY);
            glBindTexture(GL_TEXTURE_2D,texture);glBindVertexArray(vao);glBindBuffer(GL_ARRAY_BUFFER,vbo);glBufferData(GL_ARRAY_BUFFER,vertices,GL_STREAM_DRAW);
            glDrawArrays(GL_TRIANGLES,0,Math.multiplyExact(surfaces.size(),6));
        } finally {
            glBindBuffer(GL_ARRAY_BUFFER,oldBuffer);glBindVertexArray(oldVao);glUseProgram(oldProgram);
            glBindTexture(GL_TEXTURE_2D,oldTexture);if(samplers)glBindSampler(0,oldSampler);glActiveTexture(oldActive);glDepthMask(depthMask);glDepthFunc(depthFunc);
            set(GL_DEPTH_TEST,depth);set(GL_CULL_FACE,cull);set(GL_BLEND,blend);
        }
    }
    private static void set(int state,boolean enabled) { if(enabled)glEnable(state);else glDisable(state); }
    private void initialize() {
        int vertex=shader(GL_VERTEX_SHADER,"#version 150\nin vec4 Position;void main(){gl_Position=Position;}");
        int fragment=shader(GL_FRAGMENT_SHADER,"#version 150\nuniform sampler2D Reflection;uniform vec2 Size;uniform vec2 SampleScale;out vec4 FragColor;void main(){FragColor=texture(Reflection,(gl_FragCoord.xy/Size)*SampleScale);}");
        int oldVao=glGetInteger(GL_VERTEX_ARRAY_BINDING),oldBuffer=glGetInteger(GL_ARRAY_BUFFER_BINDING);
        try {
            program=glCreateProgram();glAttachShader(program,vertex);glAttachShader(program,fragment);glBindAttribLocation(program,0,"Position");glLinkProgram(program);
            if(glGetProgrami(program,GL_LINK_STATUS)==GL_FALSE)throw new IllegalStateException(glGetProgramInfoLog(program));
            vao=glGenVertexArrays();vbo=glGenBuffers();glBindVertexArray(vao);glBindBuffer(GL_ARRAY_BUFFER,vbo);
            glVertexAttribPointer(0,4,GL_FLOAT,false,16,0L);glEnableVertexAttribArray(0);
        } finally { glDeleteShader(vertex);glDeleteShader(fragment);glBindBuffer(GL_ARRAY_BUFFER,oldBuffer);glBindVertexArray(oldVao); }
    }
    private static int shader(int type,String source) {
        int shader=glCreateShader(type);glShaderSource(shader,source);glCompileShader(shader);
        if(glGetShaderi(shader,GL_COMPILE_STATUS)==GL_FALSE) {String log=glGetShaderInfoLog(shader);glDeleteShader(shader);throw new IllegalStateException(log);}return shader;
    }
    @Override public void close() { if(vbo!=0)glDeleteBuffers(vbo);if(vao!=0)glDeleteVertexArrays(vao);if(program!=0)glDeleteProgram(program);vbo=vao=program=0;staging=null;meshes.clear(); }
}
