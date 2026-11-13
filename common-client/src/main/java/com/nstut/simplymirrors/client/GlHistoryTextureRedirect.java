package com.nstut.simplymirrors.client;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.lwjgl.opengl.GL33.*;
import static org.lwjgl.opengl.GL42.*;

/**
 * Experimental zero-copy history storage; opt-in only, pending native acceptance.
 * Iris texture bind/copy/mipmap entry points and cached FBOs must all route
 * through this lease before it can replace GlTextureSnapshot. The original
 * textures stay untouched; only cleared, same-format scratch images are drawn.
 * Requires native image-isolation tests before any Minecraft A/B activation.
 */
final class GlHistoryTextureRedirect implements AutoCloseable {
    private static GlHistoryTextureRedirect current;
    private static boolean reported;
    private static boolean lazyReported;
    private static final boolean LAZY_CLEAR = Boolean.getBoolean("simplymirrors.lazyHistoryClear");
    private final java.util.Set<Integer> units=new java.util.LinkedHashSet<Integer>();
    private final java.util.Set<Integer> imageUnits=new java.util.LinkedHashSet<Integer>();
    private static final ArrayDeque<Storage> FREE = new ArrayDeque<Storage>();
    private static final long MAX_BYTES = 256L * 1024 * 1024;
    private static long cachedBytes;
    private final Map<Integer,Storage> images = new LinkedHashMap<Integer,Storage>();
    private final Map<Long,Attachment> attachments = new LinkedHashMap<Long,Attachment>();
    private boolean closed;
    private int storageSetupDepth;

    static GlHistoryTextureRedirect acquire(int[] originals) {
        if (!org.lwjgl.opengl.GL.getCapabilities().OpenGL44)
            throw new IllegalStateException("Texture redirection prototype requires OpenGL 4.4");
        if(current!=null)throw new IllegalStateException("Nested texture history lease");
        GlHistoryTextureRedirect lease = new GlHistoryTextureRedirect();
        int bound = glGetInteger(GL_TEXTURE_BINDING_2D);
        int unpack = glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
        try {
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
            for (int original : originals) {
                if (lease.images.containsKey(original)) continue;
                glBindTexture(GL_TEXTURE_2D, original);
                int width=glGetTexLevelParameteri(GL_TEXTURE_2D,0,GL_TEXTURE_WIDTH);
                int height=glGetTexLevelParameteri(GL_TEXTURE_2D,0,GL_TEXTURE_HEIGHT);
                int format=glGetTexLevelParameteri(GL_TEXTURE_2D,0,GL_TEXTURE_INTERNAL_FORMAT);
                int type=glGetTexLevelParameteri(GL_TEXTURE_2D,0,GL_TEXTURE_RED_TYPE);
                if(width<=0 || height<=0)throw new IllegalStateException("Missing history storage");
                int levels=32-Integer.numberOfLeadingZeros(Math.max(width,height));
                int[] widths=new int[levels],heights=new int[levels];
                for(int level=0;level<levels;level++) {
                    widths[level]=glGetTexLevelParameteri(GL_TEXTURE_2D,level,GL_TEXTURE_WIDTH);
                    heights[level]=glGetTexLevelParameteri(GL_TEXTURE_2D,level,GL_TEXTURE_HEIGHT);
                }
                // Capture sampling state before binding scratch. Noninteger storage
                // accepts float transfer data; integer histories require *_INTEGER.
                int[] names={GL_TEXTURE_MIN_FILTER,GL_TEXTURE_MAG_FILTER,GL_TEXTURE_WRAP_S,
                    GL_TEXTURE_WRAP_T,GL_TEXTURE_WRAP_R,GL_TEXTURE_BASE_LEVEL,GL_TEXTURE_MAX_LEVEL,
                    GL_TEXTURE_COMPARE_MODE,GL_TEXTURE_COMPARE_FUNC};
                int[] values=new int[names.length];
                for(int i=0;i<names.length;i++)values[i]=glGetTexParameteri(GL_TEXTURE_2D,names[i]);
                float minLod=glGetTexParameterf(GL_TEXTURE_2D,GL_TEXTURE_MIN_LOD);
                float maxLod=glGetTexParameterf(GL_TEXTURE_2D,GL_TEXTURE_MAX_LOD);
                float bias=glGetTexParameterf(GL_TEXTURE_2D,GL_TEXTURE_LOD_BIAS);
                float[] border=new float[4];glGetTexParameterfv(GL_TEXTURE_2D,GL_TEXTURE_BORDER_COLOR,border);
                int[] swizzle=new int[4];glGetTexParameteriv(GL_TEXTURE_2D,GL_TEXTURE_SWIZZLE_RGBA,swizzle);
                boolean anisotropic=org.lwjgl.opengl.GL.getCapabilities().GL_EXT_texture_filter_anisotropic;
                float anisotropy=anisotropic?glGetTexParameterf(GL_TEXTURE_2D,34046):1;
                Storage scratch=take(format,width,height,type,widths,heights);
                scratch.cleared=false;
                lease.images.put(original,scratch);
                glBindTexture(GL_TEXTURE_2D,scratch.id);
                for(int i=0;i<names.length;i++)glTexParameteri(GL_TEXTURE_2D,names[i],values[i]);
                glTexParameterf(GL_TEXTURE_2D,GL_TEXTURE_MIN_LOD,minLod);
                glTexParameterf(GL_TEXTURE_2D,GL_TEXTURE_MAX_LOD,maxLod);
                glTexParameterf(GL_TEXTURE_2D,GL_TEXTURE_LOD_BIAS,bias);
                glTexParameterfv(GL_TEXTURE_2D,GL_TEXTURE_BORDER_COLOR,border);
                glTexParameteriv(GL_TEXTURE_2D,GL_TEXTURE_SWIZZLE_RGBA,swizzle);
                if(anisotropic)glTexParameterf(GL_TEXTURE_2D,34046,anisotropy);
                if(!LAZY_CLEAR)clear(scratch);
            }
            current=lease;
            return lease;
        } catch(RuntimeException | Error failure) {
            try {lease.close();}catch(RuntimeException | Error cleanup){failure.addSuppressed(cleanup);}
            throw failure;
        } finally {
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER,unpack);glBindTexture(GL_TEXTURE_2D,bound);
        }
    }

    // Parameter-only DSA calls do not read image contents. In particular,
    // Oculus resets filters for every target after the final draw.
    static int map(int original) {
        Storage s=current==null?null:current.images.get(original);
        return s==null?original:s.id;
    }
    static int content(int original) {return current==null?original:current.texture(original);}
    static boolean active(){return current!=null;}
    static void beginStorageSetup(){if(LAZY_CLEAR && current!=null)current.storageSetupDepth++;}
    static void endStorageSetup(){if(current!=null && current.storageSetupDepth>0)current.storageSetupDepth--;}
    static void boundFramebuffer(int framebuffer){if(current!=null)current.framebuffer(framebuffer);}
    static int bind(int target,int unit,int original) {
        int mapped=target==GL_TEXTURE_2D?content(original):map(original);
        if(current!=null && target==GL_TEXTURE_2D && mapped!=original)current.units.add(unit);
        return mapped;
    }
    static int bindCurrent(int original) {
        return bind(GL_TEXTURE_2D,glGetInteger(GL_ACTIVE_TEXTURE)-GL_TEXTURE0,original);
    }
    static int bindImage(int unit,int original) {
        int mapped=content(original);
        if(current!=null && mapped!=original)current.imageUnits.add(unit);
        return mapped;
    }
    private int original(int scratch) {
        for(Map.Entry<Integer,Storage> entry:images.entrySet())
            if(entry.getValue().id==scratch)return entry.getKey();
        return scratch;
    }
    int texture(int original) {
        Storage scratch=closed?null:images.get(original);
        if(scratch!=null)clear(scratch);
        return scratch==null?original:scratch.id;
    }

    /** Clear before the first possible read OR partial write. Untouched private
     * images have no consumer and need no bandwidth-consuming initialization.
     * Every allocated mip still gets the exact original clear when used.
     */
    private static void clear(Storage scratch) {
        if(scratch.cleared)return;
        int unpack=LAZY_CLEAR?glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING):0;
        try {
            if(LAZY_CLEAR)glBindBuffer(GL_PIXEL_UNPACK_BUFFER,0);
            for(int level=0;level<scratch.widths.length;level++) {
                if(scratch.widths[level]==0 || scratch.heights[level]==0)continue;
                if(scratch.type==GL_INT)org.lwjgl.opengl.GL44.glClearTexImage(scratch.id,level,GL_RGBA_INTEGER,GL_INT,new int[4]);
                else if(scratch.type==GL_UNSIGNED_INT)org.lwjgl.opengl.GL44.glClearTexImage(scratch.id,level,GL_RGBA_INTEGER,GL_UNSIGNED_INT,new int[4]);
                else org.lwjgl.opengl.GL44.glClearTexImage(scratch.id,level,GL_RGBA,GL_FLOAT,new float[4]);
            }
            scratch.cleared=true;
        } finally {if(LAZY_CLEAR)glBindBuffer(GL_PIXEL_UNPACK_BUFFER,unpack);}
    }

    /** Reattach cached Iris FBOs without mutating Iris's logical attachment map. */
    void framebuffer(int framebuffer) {
        if(closed || framebuffer<=0)return;
        int bound=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        try {
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER,framebuffer);
            int count=glGetInteger(GL_MAX_COLOR_ATTACHMENTS);
            for(int i=0;i<count;i++) {
                int point=GL_COLOR_ATTACHMENT0+i;
                int kind=glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER,point,GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
                if(kind!=GL_TEXTURE)continue;
                int original=glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER,point,GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
                Storage scratch=images.get(original);
                if(scratch==null) {
                    // Depth-target setup already redirected this cached FBO.
                    // Its later real bind is the first potential color access.
                    for(Storage s:images.values())if(s.id==original) {
                        if(storageSetupDepth==0)clear(s);
                        break;
                    }
                    continue;
                }
                if(storageSetupDepth==0)clear(scratch);
                int level=glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER,point,GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL);
                int face=glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER,point,GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_CUBE_MAP_FACE);
                if(face!=0)throw new IllegalStateException("Non-2D history attachment");
                long key=((long)framebuffer<<32)|(point&0xffffffffL);
                attachments.put(key,new Attachment(framebuffer,point,original,level));
                glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER,point,GL_TEXTURE_2D,scratch.id,level);
            }
        } finally {glBindFramebuffer(GL_DRAW_FRAMEBUFFER,bound);}
    }

    @Override public void close() {
        if(closed)return;closed=true;
        if(current==this)current=null;
        if(LAZY_CLEAR && !lazyReported && !units.isEmpty() && !attachments.isEmpty()) {
            int initialized=0;
            for(Storage s:images.values())if(s.cleared)initialized++;
            System.out.println("SIMPLY_MIRRORS_LAZY_HISTORY_CLEAR initialized="+initialized+" total="+images.size());
            lazyReported=true;
        }
        if(!reported && !units.isEmpty() && !attachments.isEmpty()) {
            System.out.println("SIMPLY_MIRRORS_HISTORY_REDIRECT_ACTIVE textures="+images.size()+" units="+units.size()+" attachments="+attachments.size());
            reported=true;
        }
        int bound=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int activeUnit=glGetInteger(GL_ACTIVE_TEXTURE);
        try {
            for(Integer unit:units) {
                glActiveTexture(GL_TEXTURE0+unit);
                int texture=glGetInteger(GL_TEXTURE_BINDING_2D),main=original(texture);
                if(main!=texture)glBindTexture(GL_TEXTURE_2D,main);
            }
            for(Integer unit:imageUnits) {
                int texture=org.lwjgl.opengl.GL30.glGetIntegeri(GL_IMAGE_BINDING_NAME,unit),main=original(texture);
                if(main!=texture)org.lwjgl.opengl.GL42.glBindImageTexture(unit,main,
                    org.lwjgl.opengl.GL30.glGetIntegeri(GL_IMAGE_BINDING_LEVEL,unit),
                    org.lwjgl.opengl.GL30.glGetIntegeri(GL_IMAGE_BINDING_LAYERED,unit)!=0,
                    org.lwjgl.opengl.GL30.glGetIntegeri(GL_IMAGE_BINDING_LAYER,unit),
                    org.lwjgl.opengl.GL30.glGetIntegeri(GL_IMAGE_BINDING_ACCESS,unit),
                    org.lwjgl.opengl.GL30.glGetIntegeri(GL_IMAGE_BINDING_FORMAT,unit));
            }
            for(Attachment a:attachments.values()) {
                if(!glIsFramebuffer(a.framebuffer))continue;
                glBindFramebuffer(GL_DRAW_FRAMEBUFFER,a.framebuffer);
                glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER,a.point,GL_TEXTURE_2D,a.original,a.level);
            }
        } finally {
            glActiveTexture(activeUnit);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER,bound);
            for(Storage s:images.values())give(s);
            images.clear();attachments.clear();
        }
    }

    private static Storage take(int format,int width,int height,int type,int[] widths,int[] heights) {
        for(java.util.Iterator<Storage> it=FREE.iterator();it.hasNext();) {
            Storage s=it.next();
            if(s.format==format && s.width==width && s.height==height &&
                    java.util.Arrays.equals(s.widths,widths) && java.util.Arrays.equals(s.heights,heights)) {
                it.remove();cachedBytes-=s.bytes;return s;
            }
        }
        int id=glGenTextures();glBindTexture(GL_TEXTURE_2D,id);
        try {
            for(int level=0;level<widths.length;level++) {
                if(widths[level]==0 || heights[level]==0)continue;
                glTexImage2D(GL_TEXTURE_2D,level,format,widths[level],heights[level],0,
                    type==GL_INT || type==GL_UNSIGNED_INT?GL_RGBA_INTEGER:GL_RGBA,
                    type==GL_INT?GL_INT:type==GL_UNSIGNED_INT?GL_UNSIGNED_INT:GL_FLOAT,(ByteBuffer)null);
            }
            return new Storage(id,format,width,height,type,widths.clone(),heights.clone());
        } catch(RuntimeException | Error failure) {glDeleteTextures(id);throw failure;}
    }
    private static void give(Storage storage) {
        if(storage.bytes>MAX_BYTES){glDeleteTextures(storage.id);return;}
        while(!FREE.isEmpty() && cachedBytes+storage.bytes>MAX_BYTES) {
            Storage old=FREE.removeFirst();cachedBytes-=old.bytes;glDeleteTextures(old.id);
        }
        FREE.addLast(storage);cachedBytes+=storage.bytes;
    }
    static void releaseScratch() {
        while(!FREE.isEmpty())glDeleteTextures(FREE.removeFirst().id);
        cachedBytes=0;
    }
    private static final class Storage {
        final int id,format,width,height,type;final int[] widths,heights;final long bytes;
        boolean cleared;
        Storage(int id,int format,int width,int height,int type,int[] widths,int[] heights) {
            this.id=id;this.format=format;this.width=width;this.height=height;this.widths=widths;this.heights=heights;
            this.type=type;
            long count=0;for(int i=0;i<widths.length;i++)count+=(long)widths[i]*heights[i];bytes=count*16;
        }
    }
    private static final class Attachment {
        final int framebuffer,point,original,level;
        Attachment(int f,int p,int o,int l){framebuffer=f;point=p;original=o;level=l;}
    }
}
