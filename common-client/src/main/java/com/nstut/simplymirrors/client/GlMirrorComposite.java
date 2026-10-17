package com.nstut.simplymirrors.client;

import com.nstut.simplymirrors.math.MirrorMatrices;
import com.nstut.simplymirrors.math.MirrorSurface;
import org.lwjgl.BufferUtils;
import java.nio.FloatBuffer;
import static org.lwjgl.opengl.GL33.*;

/** Projectively samples the reflection at its screen-space location, retaining the world's depth test. */
public final class GlMirrorComposite implements AutoCloseable {
    private int program, vao, vbo;
    public void draw(int texture,int width,int height,float[] viewProjection,double eyeX,double eyeY,double eyeZ,MirrorSurface surface) {
        if(program==0) initialize();
        int oldProgram=glGetInteger(GL_CURRENT_PROGRAM),oldVao=glGetInteger(GL_VERTEX_ARRAY_BINDING),oldBuffer=glGetInteger(GL_ARRAY_BUFFER_BINDING);
        int oldActive=glGetInteger(GL_ACTIVE_TEXTURE);glActiveTexture(GL_TEXTURE0);int oldTexture=glGetInteger(GL_TEXTURE_BINDING_2D);
        boolean samplers=org.lwjgl.opengl.GL.getCapabilities().OpenGL33||org.lwjgl.opengl.GL.getCapabilities().GL_ARB_sampler_objects;
        int oldSampler=samplers?glGetInteger(GL_SAMPLER_BINDING):0;
        if(samplers)glBindSampler(0,0);
        boolean depth=glIsEnabled(GL_DEPTH_TEST),cull=glIsEnabled(GL_CULL_FACE),blend=glIsEnabled(GL_BLEND);
        boolean depthMask=glGetBoolean(GL_DEPTH_WRITEMASK);int depthFunc=glGetInteger(GL_DEPTH_FUNC);
        try {
            FloatBuffer vertices=BufferUtils.createFloatBuffer(24);
            double[][] corners=surface.corners();
            for(int index:new int[]{0,1,2,0,2,3}) {
                double[] p=corners[index];double[] clip=MirrorMatrices.transform(viewProjection,p[0]-eyeX,p[1]-eyeY,p[2]-eyeZ,1);
                vertices.put((float)clip[0]).put((float)clip[1]).put((float)(clip[2]-clip[3]*0.00001)).put((float)clip[3]);
            }
            vertices.flip();glEnable(GL_DEPTH_TEST);glDepthFunc(GL_LEQUAL);glDepthMask(false);glDisable(GL_CULL_FACE);glDisable(GL_BLEND);
            glUseProgram(program);glUniform1i(glGetUniformLocation(program,"Reflection"),0);glUniform2f(glGetUniformLocation(program,"Size"),width,height);
            glBindTexture(GL_TEXTURE_2D,texture);glBindVertexArray(vao);glBindBuffer(GL_ARRAY_BUFFER,vbo);glBufferData(GL_ARRAY_BUFFER,vertices,GL_STREAM_DRAW);
            glDrawArrays(GL_TRIANGLES,0,6);
        } finally {
            glBindBuffer(GL_ARRAY_BUFFER,oldBuffer);glBindVertexArray(oldVao);glUseProgram(oldProgram);
            glBindTexture(GL_TEXTURE_2D,oldTexture);if(samplers)glBindSampler(0,oldSampler);glActiveTexture(oldActive);glDepthMask(depthMask);glDepthFunc(depthFunc);
            set(GL_DEPTH_TEST,depth);set(GL_CULL_FACE,cull);set(GL_BLEND,blend);
        }
    }
    private static void set(int state,boolean enabled) { if(enabled)glEnable(state);else glDisable(state); }
    private void initialize() {
        int vertex=shader(GL_VERTEX_SHADER,"#version 150\nin vec4 Position;void main(){gl_Position=Position;}");
        int fragment=shader(GL_FRAGMENT_SHADER,"#version 150\nuniform sampler2D Reflection;uniform vec2 Size;out vec4 FragColor;void main(){FragColor=texture(Reflection,gl_FragCoord.xy/Size);}");
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
    @Override public void close() { if(vbo!=0)glDeleteBuffers(vbo);if(vao!=0)glDeleteVertexArrays(vao);if(program!=0)glDeleteProgram(program);vbo=vao=program=0; }
}
