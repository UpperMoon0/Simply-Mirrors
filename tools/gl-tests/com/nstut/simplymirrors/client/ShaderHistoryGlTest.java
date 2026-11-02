package com.nstut.simplymirrors.client;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;

/** Runs the production snapshot code against a real OpenGL context, without Minecraft. */
public final class ShaderHistoryGlTest {
    public static void main(String[] args) throws Exception {
        if (!glfwInit()) throw new AssertionError("GLFW initialization failed");
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        long window=glfwCreateWindow(64, 64, "Shader history regression", 0, 0);
        if (window==0) throw new AssertionError("OpenGL context unavailable");
        try {
            glfwMakeContextCurrent(window); GL.createCapabilities();
            System.out.println("OpenGL: "+glGetString(GL_VERSION)+" / "+glGetString(GL_RENDERER));
            for (int format : new int[]{GL_RGBA8, GL_SRGB8_ALPHA8, GL_RGBA16F, GL_RGBA32F, GL_R32I, GL_R32UI, GL_RGB8_SNORM, GL_RGBA8_SNORM, GL_RGB16F, GL_R11F_G11F_B10F, GL_R8}) exercise(format);
            depthStorageParity();
            depthStencilAttachmentCollision();
            depthStencilArrayLayerRestoration();
            System.out.println("PASS: world/shadow main/alt contents, zero history, all mip levels, 11 integer/float/sRGB/SNORM formats, shadow flips, sampler flags, GL state, repeated/exception cleanup");
        } finally { glfwDestroyWindow(window); glfwTerminate(); }
    }

    /** Regression: the camera-depth reallocation restores caller binding and
     * preserves the source while aligning the mirror texture's internal format. */
    private static void depthStorageParity() {
        int original = glGetInteger(GL_TEXTURE_BINDING_2D);
        int main = glGenTextures();
        int mirror = glGenTextures();
        try {
            glBindTexture(GL_TEXTURE_2D, main);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT32F, 16, 16, 0,
                    GL_DEPTH_COMPONENT, GL_FLOAT, (ByteBuffer) null);
            glBindTexture(GL_TEXTURE_2D, mirror);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, 16, 16, 0,
                    GL_DEPTH_COMPONENT, GL_UNSIGNED_INT, (ByteBuffer) null);
            int binding = glGetInteger(GL_TEXTURE_BINDING_2D);
            if (!DepthTextureParity.match(main, mirror, 16, 16))
                throw new AssertionError("Mismatched mirror depth was not upgraded");
            if (glGetInteger(GL_TEXTURE_BINDING_2D) != binding)
                throw new AssertionError("Mirror depth format adapter leaked texture binding");
            if (glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_INTERNAL_FORMAT)
                    != GL_DEPTH_COMPONENT32F) throw new AssertionError("Incorrect mirror depth format");
            if (DepthTextureParity.match(main, mirror, 16, 16))
                throw new AssertionError("Matching depth storage was allocated twice");
            glBindTexture(GL_TEXTURE_2D, main);
            if (glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_INTERNAL_FORMAT)
                    != GL_DEPTH_COMPONENT32F) throw new AssertionError("Main depth format changed");
            int fbo = glGenFramebuffers();
            int color = glGenTextures();
            try {
                glBindTexture(GL_TEXTURE_2D, color);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, 16, 16, 0,
                        GL_RGBA, GL_FLOAT, (ByteBuffer) null);
                int previous = glGetInteger(GL_FRAMEBUFFER_BINDING);
                glBindFramebuffer(GL_FRAMEBUFFER, fbo);
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                        GL_TEXTURE_2D, color, 0);
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
                        GL_TEXTURE_2D, mirror, 0);
                int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
                glBindFramebuffer(GL_FRAMEBUFFER, previous);
                if (status != GL_FRAMEBUFFER_COMPLETE)
                    throw new AssertionError("Reallocated mirror depth cannot support an Iris color FBO: " + status);
            } finally {
                glDeleteTextures(color);
                glDeleteFramebuffers(fbo);
            }
            if (glGetError() != GL_NO_ERROR)
                throw new AssertionError("Depth storage adapter caused a GL error");
        } finally {
            glBindTexture(GL_TEXTURE_2D, original);
            glDeleteTextures(main);
            glDeleteTextures(mirror);
        }
    }


    /**
     * Iris can retain a packed stencil attachment from its main gbuffer when
     * the depth attachment is replaced with the mirror's depth-only texture.
     * Some GL drivers report FRAMEBUFFER_UNSUPPORTED for that split pair.
     * Verify scoped correction/restoration without altering the color/depth
     * textures, READ binding, or any complete framebuffers.
     */
    private static void depthStencilAttachmentCollision() {
        int originalDraw=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int originalRead=glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        int originalTexture=glGetInteger(GL_TEXTURE_BINDING_2D);
        int framebuffer=glGenFramebuffers();
        int color=glGenTextures(),depth=glGenTextures(),stencil=glGenTextures();
        try {
            glBindTexture(GL_TEXTURE_2D,color);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
            glTexImage2D(GL_TEXTURE_2D,0,GL_R11F_G11F_B10F,32,32,0,GL_RGB,
                    GL_FLOAT,(ByteBuffer)null);
            glBindTexture(GL_TEXTURE_2D,depth);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
            glTexImage2D(GL_TEXTURE_2D,0,GL_DEPTH_COMPONENT,32,32,0,
                    GL_DEPTH_COMPONENT,GL_UNSIGNED_INT,(ByteBuffer)null);
            glBindTexture(GL_TEXTURE_2D,stencil);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
            glTexImage2D(GL_TEXTURE_2D,0,GL_DEPTH24_STENCIL8,32,32,0,
                    GL_DEPTH_STENCIL,GL_UNSIGNED_INT_24_8,(ByteBuffer)null);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER,framebuffer);
            glFramebufferTexture(GL_DRAW_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,color,0);
            glFramebufferTexture(GL_DRAW_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,depth,0);
            glFramebufferTexture(GL_DRAW_FRAMEBUFFER,GL_STENCIL_ATTACHMENT,stencil,0);
            int originalStatus=glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER);
            ReflectionFramebufferParity transaction=new ReflectionFramebufferParity();
            try {
                boolean changed=transaction.repairBoundDrawFramebuffer();
                System.out.println("Iris-like split FBO status="+originalStatus
                        +" repair="+changed+" color=R11F_G11F_B10F depth=GL_DEPTH_COMPONENT");
                if(originalStatus==GL_FRAMEBUFFER_COMPLETE) {
                    if(changed) throw new AssertionError("Complete framebuffer altered");
                } else {
                    if(!changed) throw new AssertionError(
                            "Split depth/stencil framebuffer was not repaired: "+originalStatus);
                    if(glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)
                        throw new AssertionError("Reflection framebuffer remains unsupported");
                    if(transaction.repairBoundDrawFramebuffer())
                        throw new AssertionError("Repeated repair altered existing scope");
                    int detached=glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER,
                            GL_STENCIL_ATTACHMENT,GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
                    if(detached!=GL_NONE)
                        throw new AssertionError("Incompatible stencil still bound");
                }
            } finally {
                transaction.close();
                transaction.close();
            }
            if(glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER)!=originalStatus)
                throw new AssertionError("Original framebuffer status was not restored");
            int restored=glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER,
                    GL_STENCIL_ATTACHMENT,GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            if(restored!=stencil)
                throw new AssertionError("Main stencil attachment was not restored");
            if(glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING)!=framebuffer ||
                    glGetInteger(GL_READ_FRAMEBUFFER_BINDING)!=originalRead)
                throw new AssertionError("Scoped repair leaked framebuffer binding");
            if(glGetError()!=GL_NO_ERROR)
                throw new AssertionError("Scoped reflection repair caused GL error");

            // Iris can replace stencil A with B during one reflection scope.
            // Verify both a complete replacement and another incompatible swap.
            int stencilB=glGenTextures();
            try {
                glBindTexture(GL_TEXTURE_2D,stencilB);
                glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);
                glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
                glTexImage2D(GL_TEXTURE_2D,0,GL_DEPTH24_STENCIL8,32,32,0,
                        GL_DEPTH_STENCIL,GL_UNSIGNED_INT_24_8,(ByteBuffer)null);
                for (boolean completeTransition : new boolean[]{true,false}) {
                    glFramebufferTexture(GL_DRAW_FRAMEBUFFER,GL_STENCIL_ATTACHMENT,stencil,0);
                    ReflectionFramebufferParity scope=new ReflectionFramebufferParity();
                    try {
                        if(originalStatus!=GL_FRAMEBUFFER_COMPLETE &&
                                !scope.repairBoundDrawFramebuffer())
                            throw new AssertionError("Initial A repair failed");
                        // In the complete branch, Iris removes the split depth
                        // attachment before installing its replacement B.
                        if(completeTransition)
                            glFramebufferTexture(GL_DRAW_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,0,0);
                        glFramebufferTexture(GL_DRAW_FRAMEBUFFER,GL_STENCIL_ATTACHMENT,stencilB,0);
                        int updatedStatus=glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER);
                        if(completeTransition && updatedStatus!=GL_FRAMEBUFFER_COMPLETE)
                            throw new AssertionError("Expected complete Iris replacement");
                        boolean secondRepair=scope.repairBoundDrawFramebuffer();
                        if(completeTransition && secondRepair)
                            throw new AssertionError("Complete replacement was modified");
                        if(!completeTransition && originalStatus!=GL_FRAMEBUFFER_COMPLETE &&
                                !secondRepair)
                            throw new AssertionError("Second B repair failed");
                    } finally {
                        scope.close();
                    }
                    int current=glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER,
                            GL_STENCIL_ATTACHMENT,GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
                    if(current!=stencilB)
                        throw new AssertionError("Stale A overwrote newer Iris stencil B");
                    if(completeTransition && glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)
                        throw new AssertionError("Complete Iris replacement became incomplete");
                    if(glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING)!=framebuffer ||
                            glGetInteger(GL_READ_FRAMEBUFFER_BINDING)!=originalRead)
                        throw new AssertionError("Iris stencil transition leaked framebuffer binding");
                    glFramebufferTexture(GL_DRAW_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,depth,0);
                }
            } finally {
                glDeleteTextures(stencilB);
            }
            if(glGetError()!=GL_NO_ERROR)
                throw new AssertionError("Iris stencil transition caused GL error");

        } finally {
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER,originalDraw);
            glBindFramebuffer(GL_READ_FRAMEBUFFER,originalRead);
            glBindTexture(GL_TEXTURE_2D,originalTexture);
            glDeleteFramebuffers(framebuffer);
            glDeleteTextures(color);glDeleteTextures(depth);glDeleteTextures(stencil);
        }
    }

    /**
     * Regression for an actual single array/3D layer, not the whole texture.
     * glFramebufferTexture() would silently turn this back into a layered
     * attachment when the reflection scope exits.
     */
    private static void depthStencilArrayLayerRestoration() {
        int previousDraw=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int previousRead=glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        int previous2D=glGetInteger(GL_TEXTURE_BINDING_2D);
        int previousArray=glGetInteger(GL_TEXTURE_BINDING_2D_ARRAY);
        int framebuffer=glGenFramebuffers();
        int color=glGenTextures(),depth=glGenTextures(),stencilArray=glGenTextures();
        try {
            glBindTexture(GL_TEXTURE_2D,color);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
            glTexImage2D(GL_TEXTURE_2D,0,GL_R11F_G11F_B10F,32,32,0,
                    GL_RGB,GL_FLOAT,(ByteBuffer)null);
            glBindTexture(GL_TEXTURE_2D,depth);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
            glTexImage2D(GL_TEXTURE_2D,0,GL_DEPTH_COMPONENT,32,32,0,
                    GL_DEPTH_COMPONENT,GL_UNSIGNED_INT,(ByteBuffer)null);
            glBindTexture(GL_TEXTURE_2D_ARRAY,stencilArray);
            glTexParameteri(GL_TEXTURE_2D_ARRAY,GL_TEXTURE_MIN_FILTER,GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D_ARRAY,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
            glTexImage3D(GL_TEXTURE_2D_ARRAY,0,GL_DEPTH24_STENCIL8,32,32,2,0,
                    GL_DEPTH_STENCIL,GL_UNSIGNED_INT_24_8,(ByteBuffer)null);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER,framebuffer);
            glFramebufferTexture(GL_DRAW_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,color,0);
            glFramebufferTexture(GL_DRAW_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,depth,0);
            for (int testLayer : new int[]{0,1}) {
                glFramebufferTextureLayer(GL_DRAW_FRAMEBUFFER,GL_STENCIL_ATTACHMENT,
                        stencilArray,0,testLayer);
                int before=glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER);
                if(before==GL_FRAMEBUFFER_COMPLETE)
                    throw new AssertionError("Split depth and single-layer stencil unexpectedly complete; regression did not exercise repair");
                ReflectionFramebufferParity scope=new ReflectionFramebufferParity();
                try {
                    if(!scope.repairBoundDrawFramebuffer())
                        throw new AssertionError("Single-layer stencil collision not repaired: "+before);
                    if(glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)
                        throw new AssertionError("Single-layer repair did not make framebuffer complete");
                } finally {
                    scope.close();
                    scope.close();
                }
                if(glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER)!=before)
                    throw new AssertionError("Single-layer stencil original status not restored");
                int restored=glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER,
                        GL_STENCIL_ATTACHMENT,GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
                int restoredLayer=glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER,
                        GL_STENCIL_ATTACHMENT,GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LAYER);
                int restoredLayered=glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER,
                        GL_STENCIL_ATTACHMENT,GL_FRAMEBUFFER_ATTACHMENT_LAYERED);
                if(restored!=stencilArray || restoredLayer!=testLayer || restoredLayered!=GL_FALSE)
                    throw new AssertionError("Stencil array layer topology changed: name="+restored
                            +" layer="+restoredLayer+" layered="+restoredLayered);
                if(glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING)!=framebuffer ||
                        glGetInteger(GL_READ_FRAMEBUFFER_BINDING)!=previousRead)
                    throw new AssertionError("Single-layer repair leaked draw/read bindings");
                if(glGetError()!=GL_NO_ERROR)
                    throw new AssertionError("Single-layer stencil repair caused a GL error");
                System.out.println("PASS: stencil array single layer "+testLayer+" restored, FBO status="+before);
            }
        } finally {
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER,previousDraw);
            glBindFramebuffer(GL_READ_FRAMEBUFFER,previousRead);
            glBindTexture(GL_TEXTURE_2D,previous2D);
            glBindTexture(GL_TEXTURE_2D_ARRAY,previousArray);
            glDeleteFramebuffers(framebuffer);
            glDeleteTextures(color);
            glDeleteTextures(depth);
            glDeleteTextures(stencilArray);
        }
    }

    private static void exercise(int format) throws Exception {
        Target persistent=new Target(make(format, 16, 8, 0.25f), make(format, 8, 4, 0.75f));
        Target cleared=new Target(make(format, 4, 4, 0.50f), make(format, 4, 4, 0.50f));
        Target shadow=new Target(make(format, 8, 8, 0.3f), make(format, 8, 8, 0.6f));
        Pipeline pipeline=new Pipeline(persistent, cleared, shadow);
        Map<Integer,List<byte[]>> original=new LinkedHashMap<>();
        for (int texture : new int[]{persistent.main,persistent.alt,cleared.main,cleared.alt,shadow.main,shadow.alt}) original.put(texture, pixels(texture,format));
        int read=glGenFramebuffers(), draw=glGenFramebuffers(), rb=glGenRenderbuffers();
        glBindFramebuffer(GL_READ_FRAMEBUFFER,read); glBindFramebuffer(GL_DRAW_FRAMEBUFFER,draw);
        glBindRenderbuffer(GL_RENDERBUFFER,rb);
        glActiveTexture(GL_TEXTURE3); glBindTexture(GL_TEXTURE_2D,cleared.main);
        glEnable(GL_SCISSOR_TEST); glScissor(1,1,1,1); glEnable(GL_FRAMEBUFFER_SRGB);
        glColorMaski(0,false,true,false,true);
        glDepthMask(false);
        for (int iteration=0; iteration<12; iteration++) {
            List<Integer> allocations=new ArrayList<>();
            ShaderRenderTargetSnapshot snapshot=ShaderRenderTargetSnapshot.capture(pipeline);
            state(read,draw,rb,cleared.main);
            // Native GL readbacks establish that both temporal identities are zeroed.
            zero(persistent.main,format); zero(persistent.alt,format); zero(shadow.main,format); zero(shadow.alt,format);
            same(cleared.main,format,original.get(cleared.main));
            Field tf=ShaderRenderTargetSnapshot.class.getDeclaredField("textures"); tf.setAccessible(true);
            Object textureSnapshot=tf.get(snapshot);
            for (String fieldName : new String[]{"textureFramebuffer","backupFramebuffer"}) {
                Field f=textureSnapshot.getClass().getDeclaredField(fieldName); f.setAccessible(true); allocations.add(f.getInt(textureSnapshot));
            }
            Field images=textureSnapshot.getClass().getDeclaredField("images"); images.setAccessible(true);
            for (Object image : (List<?>)images.get(textureSnapshot)) {
                Field backup=image.getClass().getDeclaredField("backup"); backup.setAccessible(true); allocations.add(backup.getInt(image));
            }
            try (ShaderRenderTargetSnapshot ignored=snapshot) {
                fill(persistent.main,format,0.9f); fill(persistent.alt,format,0.1f);
                glBindTexture(GL_TEXTURE_2D,persistent.main); glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);
                persistent.mipmapsOnMain=false; persistent.mipmapsOnAlt=true;
                fill(shadow.main,format,0.95f); fill(shadow.alt,format,0.05f);
                pipeline.shadowRenderTargets.flipped[0]=true;
                // Simulate a throwing reflected render; try-with-resources must recover identically.
                throw new Expected();
            } catch (Expected expected) { }
            snapshot.close(); // End/unload cleanup can safely repeat.
            if (pipeline.shadowRenderTargets.flipped[0]) throw new AssertionError("Shadow flip leaked");
            same(shadow.main,format,original.get(shadow.main)); same(shadow.alt,format,original.get(shadow.alt));
            if (!persistent.mipmapsOnMain || persistent.mipmapsOnAlt) throw new AssertionError("Sampler flags leaked");
            glBindTexture(GL_TEXTURE_2D,persistent.main);
            if (glGetTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER)!=GL_NEAREST_MIPMAP_NEAREST) throw new AssertionError("Mipmap filter leaked");
            same(persistent.main,format,original.get(persistent.main)); same(persistent.alt,format,original.get(persistent.alt));
            for (int i=0;i<allocations.size();i++) {
                boolean leaked=i<2?glIsFramebuffer(allocations.get(i)):glIsRenderbuffer(allocations.get(i));
                if(leaked) throw new AssertionError("Snapshot allocation leaked");
            }
            glBindTexture(GL_TEXTURE_2D,cleared.main); state(read,draw,rb,cleared.main);
            if (glGetError()!=GL_NO_ERROR) throw new AssertionError("OpenGL error for format "+format);
        }
        // A failure halfway through capture must preserve earlier images and caller state.
        int invalid=glGenTextures(); glBindTexture(GL_TEXTURE_2D,invalid); glBindTexture(GL_TEXTURE_2D,cleared.main);
        try { GlTextureSnapshot.capture(new int[]{persistent.main,invalid}); throw new AssertionError("Invalid storage accepted"); }
        catch (IllegalStateException expected) { }
        state(read,draw,rb,cleared.main); same(persistent.main,format,original.get(persistent.main));
        if (glGetError()!=GL_NO_ERROR) throw new AssertionError("OpenGL error after capture failure");
        glDeleteTextures(invalid);
        for (int texture : original.keySet()) glDeleteTextures(texture);
        glDeleteFramebuffers(read); glDeleteFramebuffers(draw); glDeleteRenderbuffers(rb);
    }

    private static void state(int read,int draw,int rb,int texture) {
        if (glGetInteger(GL_READ_FRAMEBUFFER_BINDING)!=read || glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING)!=draw
                || glGetInteger(GL_RENDERBUFFER_BINDING)!=rb || glGetInteger(GL_ACTIVE_TEXTURE)!=GL_TEXTURE3
                || glGetInteger(GL_TEXTURE_BINDING_2D)!=texture || !glIsEnabled(GL_SCISSOR_TEST)
                || !glIsEnabled(GL_FRAMEBUFFER_SRGB) || glGetBoolean(GL_DEPTH_WRITEMASK)) throw new AssertionError("GL state leaked");
        ByteBuffer mask=BufferUtils.createByteBuffer(4); glGetBooleanv(GL_COLOR_WRITEMASK,mask);
        if(mask.get(0)!=0 || mask.get(1)==0 || mask.get(2)!=0 || mask.get(3)==0) throw new AssertionError("Color mask leaked");
    }

    private static int make(int format,int width,int height,float value) {
        int texture=glGenTextures(); glBindTexture(GL_TEXTURE_2D,texture);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST_MIPMAP_NEAREST);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
        int levels=32-Integer.numberOfLeadingZeros(Math.max(width,height));
        for(int l=0;l<levels;l++) {
            int w=Math.max(1,width>>l), h=Math.max(1,height>>l);
            glTexImage2D(GL_TEXTURE_2D,l,format,w,h,0,integer(format)?GL_RED_INTEGER:GL_RGBA,
                    integer(format)?GL_INT:GL_FLOAT,(ByteBuffer)null);
        }
        fill(texture,format,value); return texture;
    }
    private static boolean integer(int format) { return format==GL_R32I || format==GL_R32UI; }
    private static void fill(int texture,int format,float value) {
        glBindTexture(GL_TEXTURE_2D,texture);
        for (int level=0;;level++) {
            int w=glGetTexLevelParameteri(GL_TEXTURE_2D,level,GL_TEXTURE_WIDTH),h=glGetTexLevelParameteri(GL_TEXTURE_2D,level,GL_TEXTURE_HEIGHT);
            if(w==0) break;
            if(integer(format)) {
                IntBuffer values=BufferUtils.createIntBuffer(w*h); for(int i=0;i<w*h;i++)values.put((int)(value*100)+level+i%3);values.flip();
                glTexSubImage2D(GL_TEXTURE_2D,level,0,0,w,h,GL_RED_INTEGER,GL_INT,values);
            } else {
                FloatBuffer values=BufferUtils.createFloatBuffer(w*h*4); for(int i=0;i<w*h*4;i++)values.put(value+level*0.01f+(i%4)*0.001f);values.flip();
                glTexSubImage2D(GL_TEXTURE_2D,level,0,0,w,h,GL_RGBA,GL_FLOAT,values);
            }
        }
    }
    private static List<byte[]> pixels(int texture,int format) {
        int old=glGetInteger(GL_TEXTURE_BINDING_2D); glBindTexture(GL_TEXTURE_2D,texture);
        List<byte[]> result=new ArrayList<>();
        for(int level=0;;level++) {
            int w=glGetTexLevelParameteri(GL_TEXTURE_2D,level,GL_TEXTURE_WIDTH),h=glGetTexLevelParameteri(GL_TEXTURE_2D,level,GL_TEXTURE_HEIGHT);
            if(w==0) break;
            ByteBuffer values=BufferUtils.createByteBuffer(w*h*(integer(format)?1:4)*4);
            glGetTexImage(GL_TEXTURE_2D,level,integer(format)?GL_RED_INTEGER:GL_RGBA,integer(format)?GL_INT:GL_FLOAT,values);
            byte[] bytes=new byte[values.capacity()];values.get(bytes);result.add(bytes);
        }
        glBindTexture(GL_TEXTURE_2D,old);return result;
    }
    private static void same(int texture,int format,List<byte[]> expected) {
        List<byte[]> actual=pixels(texture,format);
        if(actual.size()!=expected.size())throw new AssertionError("Mipmap levels changed");
        for(int i=0;i<actual.size();i++)if(!Arrays.equals(actual.get(i),expected.get(i)))throw new AssertionError("History image changed: format="+format+" texture="+texture+" level="+i);
    }
    private static void zero(int texture,int format) {
        boolean alpha=format!=GL_R8 && format!=GL_RGB8_SNORM && format!=GL_RGB16F && format!=GL_R11F_G11F_B10F;
        for(byte[] level:pixels(texture,format))for(int i=0;i<level.length;i++) {
            if (!integer(format) && !alpha && i%16>=12) continue; // Missing alpha reads as one.
            if(level[i]!=0)throw new AssertionError("Reflected history was not cleared");
        }
    }
    private static final class Expected extends RuntimeException { }
    public static final class Pipeline {
        private final Targets renderTargets;
        private final Shadows shadowRenderTargets;
        private final Directives packDirectives=new Directives();
        Pipeline(Target persistent,Target cleared,Target shadow){renderTargets=new Targets(persistent,cleared);shadowRenderTargets=new Shadows(shadow);}
    }
    public static final class Targets {
        private final Target[] values;
        Targets(Target...values){this.values=values;}
        public Target get(int index){return values[index];}
    }
    public static final class Shadows {
        private final Target value;
        private final boolean[] flipped={false};
        Shadows(Target value){this.value=value;}
        public int getRenderTargetCount(){return 1;}
        public Target get(int index){return value;}
        public List<Integer> getBuffersToBeCleared(){return Collections.emptyList();}
    }
    public static final class Target {
        private final int main,alt;
        private boolean mipmapsOnMain=true,mipmapsOnAlt=false;
        Target(int main,int alt){this.main=main;this.alt=alt;}
        public int getMainTexture(){return main;}
        public int getAltTexture(){return alt;}
    }
    public static final class Directives {
        public Directives getRenderTargetDirectives(){return this;}
        public Map<Integer,Setting> getRenderTargetSettings(){Map<Integer,Setting> result=new HashMap<>();result.put(0,new Setting(false));result.put(1,new Setting(true));return result;}
    }
    public static final class Setting {
        private final boolean clear;
        Setting(boolean clear){this.clear=clear;}
        public boolean shouldClear(){return clear;}
    }
}
