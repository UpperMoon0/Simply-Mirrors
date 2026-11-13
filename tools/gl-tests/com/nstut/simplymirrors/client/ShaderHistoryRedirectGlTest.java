package com.nstut.simplymirrors.client;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL44.*;
/** Actual-driver isolation regression, run only by the trusted native GPU tier. */
public final class ShaderHistoryRedirectGlTest {
    public static void main(String[] args) {
        if(!glfwInit())throw new AssertionError("GLFW init");
        glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,4);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,4);
        long window=glfwCreateWindow(32,32,"history-redirect-isolation",0,0);
        if(window==0)throw new AssertionError("Native OpenGL 4.4 context unavailable");
        try {
            glfwMakeContextCurrent(window);GL.createCapabilities();
            for(int format:new int[]{GL_RGBA8,GL_RGBA8_SNORM,GL_RGB16F,GL_R11F_G11F_B10F,GL_RGBA32I,GL_RGBA32UI})check(format);
            System.out.println("SIMPLY_MIRRORS_HISTORY_REDIRECT_GL_PASS renderer="+glGetString(GL_RENDERER));
        } finally {
            GlHistoryTextureRedirect.releaseScratch();glfwDestroyWindow(window);glfwTerminate();
        }
    }
    private static void check(int format) {
        boolean signed=format==GL_RGBA32I,integer=signed || format==GL_RGBA32UI;
        int transfer=integer?(signed?GL_INT:GL_UNSIGNED_INT):GL_FLOAT;
        int pixel=integer?GL_RGBA_INTEGER:GL_RGBA;
        int original=glGenTextures(),fbo=glGenFramebuffers();
        try {
            glBindTexture(GL_TEXTURE_2D,original);
            ByteBuffer data=BufferUtils.createByteBuffer(8*8*16);
            for(int i=0;i<8*8*4;i++)if(integer)data.putInt(i%23);else data.putFloat((i%7+1)*0.1f);
            data.flip();glTexImage2D(GL_TEXTURE_2D,0,format,8,8,0,pixel,transfer,data);
            if(integer) {
                for(int level=1;level<4;level++) {
                    int size=8>>level;
                    ByteBuffer mip=BufferUtils.createByteBuffer(size*size*16);
                    for(int i=0;i<size*size*4;i++)mip.putInt(i+level);mip.flip();
                    glTexImage2D(GL_TEXTURE_2D,level,format,size,size,0,pixel,transfer,mip);
                }
            } else glGenerateMipmap(GL_TEXTURE_2D);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST_MIPMAP_NEAREST);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_MIRRORED_REPEAT);
            byte[][] expected=read(original,pixel,transfer);
            glBindFramebuffer(GL_FRAMEBUFFER,fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,original,0);
            if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)throw new AssertionError("Original FBO incomplete");
            // Exercise the cached FBO, sampler unit, image unit and reused scratch path.
            for(int iteration=0;iteration<2;iteration++) {
                int scratch;
                try(GlHistoryTextureRedirect lease=GlHistoryTextureRedirect.acquire(new int[]{original})) {
                    scratch=lease.texture(original);
                    if(scratch==original)throw new AssertionError("History not redirected");
                    glBindTexture(GL_TEXTURE_2D,scratch);
                    if(glGetTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T)!=GL_MIRRORED_REPEAT)throw new AssertionError("Sampler state lost");
                    byte[][] empty=read(scratch,pixel,transfer);
                    // Only check stored channels: RGB/R11F synthesize alpha=1 on read.
                    int components=(format==GL_RGB16F || format==GL_R11F_G11F_B10F)?3:4;
                    for(byte[] mip:empty) {
                        ByteBuffer b=ByteBuffer.wrap(mip).order(java.nio.ByteOrder.nativeOrder());
                        for(int i=0;i<mip.length/4;i++)if(i%4<components && b.getInt(i*4)!=0)throw new AssertionError("Scratch history not zero");
                    }
                    lease.framebuffer(fbo);
                    if(glGetFramebufferAttachmentParameteri(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME)!=scratch)throw new AssertionError("Cached FBO not redirected");
                    if(integer) {
                        if(signed)glClearBufferiv(GL_COLOR,0,new int[]{5,6,7,8});
                        else glClearBufferuiv(GL_COLOR,0,new int[]{5,6,7,8});
                    } else glClearBufferfv(GL_COLOR,0,new float[]{0.9f,0.8f,0.7f,0.6f});
                    glActiveTexture(GL_TEXTURE3);
                    glBindTexture(GL_TEXTURE_2D,GlHistoryTextureRedirect.bind(GL_TEXTURE_2D,3,original));
                    if(format!=GL_RGB16F)glBindImageTexture(2,GlHistoryTextureRedirect.bindImage(2,original),0,false,0,GL_READ_WRITE,format);
                    glActiveTexture(GL_TEXTURE5);
                }
                if(glGetInteger(GL_ACTIVE_TEXTURE)!=GL_TEXTURE5)throw new AssertionError("Active texture changed");
                glActiveTexture(GL_TEXTURE3);
                if(glGetInteger(GL_TEXTURE_BINDING_2D)!=original)throw new AssertionError("Sampler texture did not restore");
                if(format!=GL_RGB16F && glGetIntegeri(GL_IMAGE_BINDING_NAME,2)!=original)throw new AssertionError("Image binding did not restore");
                if(glGetFramebufferAttachmentParameteri(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME)!=original)throw new AssertionError("FBO did not restore");
                byte[][] actual=read(original,pixel,transfer);
                if(!Arrays.deepEquals(expected,actual))throw new AssertionError("Original history/mips changed format="+format);
                if(glGetTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER)!=GL_NEAREST_MIPMAP_NEAREST)throw new AssertionError("Original filter changed");
                int error=glGetError();if(error!=GL_NO_ERROR)throw new AssertionError("GL error "+error+" format="+format);
            }
        } finally {glBindFramebuffer(GL_FRAMEBUFFER,0);glDeleteFramebuffers(fbo);glDeleteTextures(original);}
    }
    private static byte[][] read(int texture,int pixel,int type) {
        glBindTexture(GL_TEXTURE_2D,texture);byte[][] result=new byte[4][];
        for(int level=0;level<4;level++) {
            int size=8>>level;ByteBuffer data=BufferUtils.createByteBuffer(size*size*16);
            glGetTexImage(GL_TEXTURE_2D,level,pixel,type,data);byte[] bytes=new byte[data.capacity()];data.get(bytes);result[level]=bytes;
        }
        return result;
    }
}
