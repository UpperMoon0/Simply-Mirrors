package com.nstut.simplymirrors.client;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL33.*;

/** A render-thread transaction for the contents of existing color textures. */
final class GlTextureSnapshot implements AutoCloseable {
    private final List<Image> images = new ArrayList<>();
    private final List<Texture> textures = new ArrayList<>();
    private int textureFramebuffer, backupFramebuffer;
    private boolean closed;

    static GlTextureSnapshot capture(int[] textureIds) {
        GlTextureSnapshot snapshot = new GlTextureSnapshot();
        try (State ignored = new State()) {
            snapshot.textureFramebuffer = glGenFramebuffers();
            snapshot.backupFramebuffer = glGenFramebuffers();
            for (int texture : textureIds) snapshot.save(texture);
            // A new viewpoint has no temporal history. In particular, Complementary
            // rejects zero history rather than blending the main camera into a mirror.
            for (Image image : snapshot.images) snapshot.clear(image);
            return snapshot;
        } catch (RuntimeException | Error failure) {
            try { snapshot.close(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private void save(int texture) {
        glBindTexture(GL_TEXTURE_2D, texture);
        textures.add(new Texture(texture, glGetTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER),
                glGetTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER)));
        int width = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH);
        int height = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT);
        if (width <= 0 || height <= 0) throw new IllegalStateException("Shader history texture has no storage");
        int levels = 32 - Integer.numberOfLeadingZeros(Math.max(width, height));
        for (int level = 0; level < levels; level++) {
            int w = glGetTexLevelParameteri(GL_TEXTURE_2D, level, GL_TEXTURE_WIDTH);
            int h = glGetTexLevelParameteri(GL_TEXTURE_2D, level, GL_TEXTURE_HEIGHT);
            if (w == 0 || h == 0) continue;
            int format = glGetTexLevelParameteri(GL_TEXTURE_2D, level, GL_TEXTURE_INTERNAL_FORMAT);
            int type = glGetTexLevelParameteri(GL_TEXTURE_2D, level, GL_TEXTURE_RED_TYPE);
            Image image = new Image(texture, level, w, h, type, glGenRenderbuffers());
            images.add(image); // Own the allocation before any operation can fail.
            glBindRenderbuffer(GL_RENDERBUFFER, image.backup);
            glRenderbufferStorage(GL_RENDERBUFFER, format, w, h);
            attachTexture(GL_READ_FRAMEBUFFER, image);
            attachBackup(GL_DRAW_FRAMEBUFFER, image);
            copy(image);
            image.saved = true;
        }
    }

    private void attachTexture(int target, Image image) {
        glBindFramebuffer(target, textureFramebuffer);
        glFramebufferTexture2D(target, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, image.texture, image.level);
        complete(target);
    }

    private void attachBackup(int target, Image image) {
        glBindFramebuffer(target, backupFramebuffer);
        glFramebufferRenderbuffer(target, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, image.backup);
        complete(target);
    }

    private static void complete(int target) {
        if (glCheckFramebufferStatus(target) != GL_FRAMEBUFFER_COMPLETE)
            throw new IllegalStateException("Cannot isolate shader history framebuffer");
    }

    private static void copy(Image image) {
        glBlitFramebuffer(0, 0, image.width, image.height, 0, 0, image.width, image.height,
                GL_COLOR_BUFFER_BIT, GL_NEAREST);
    }

    private void clear(Image image) {
        attachTexture(GL_DRAW_FRAMEBUFFER, image);
        if (image.type == GL_INT) glClearBufferiv(GL_COLOR, 0, new int[4]);
        else if (image.type == GL_UNSIGNED_INT) glClearBufferuiv(GL_COLOR, 0, new int[4]);
        else glClearBufferfv(GL_COLOR, 0, new float[4]);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        try (State ignored = new State()) {
            try {
                Throwable failure = null;
                for (Image image : images) {
                    if (!image.saved) continue;
                    try {
                        attachBackup(GL_READ_FRAMEBUFFER, image);
                        attachTexture(GL_DRAW_FRAMEBUFFER, image);
                        copy(image);
                    } catch (RuntimeException | Error problem) {
                        if (failure == null) failure = problem; else failure.addSuppressed(problem);
                    }
                }
                for (Texture texture : textures) {
                    try {
                        glBindTexture(GL_TEXTURE_2D, texture.id);
                        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, texture.minFilter);
                        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, texture.magFilter);
                    } catch (RuntimeException | Error problem) {
                        if (failure == null) failure = problem; else failure.addSuppressed(problem);
                    }
                }
                if (failure instanceof Error) throw (Error) failure;
                if (failure != null) throw (RuntimeException) failure;
            } finally {
                for (Image image : images) glDeleteRenderbuffers(image.backup);
                if (textureFramebuffer != 0) glDeleteFramebuffers(textureFramebuffer);
                if (backupFramebuffer != 0) glDeleteFramebuffers(backupFramebuffer);
                images.clear(); textures.clear();
            }
        }
    }

    private static final class Image {
        final int texture, level, width, height, type, backup;
        boolean saved;
        Image(int texture, int level, int width, int height, int type, int backup) {
            this.texture=texture; this.level=level; this.width=width; this.height=height; this.type=type; this.backup=backup;
        }
    }

    private static final class Texture {
        final int id, minFilter, magFilter;
        Texture(int id, int minFilter, int magFilter) { this.id=id; this.minFilter=minFilter; this.magFilter=magFilter; }
    }

    /** Raw GL binds are temporary, so Minecraft's binding caches remain correct. */
    private static final class State implements AutoCloseable {
        final int read=glGetInteger(GL_READ_FRAMEBUFFER_BINDING), draw=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        final int texture=glGetInteger(GL_TEXTURE_BINDING_2D), renderbuffer=glGetInteger(GL_RENDERBUFFER_BINDING);
        final boolean scissor=glIsEnabled(GL_SCISSOR_TEST), srgb=glIsEnabled(GL_FRAMEBUFFER_SRGB);
        final ByteBuffer colorMask=BufferUtils.createByteBuffer(4);
        State() {
            glGetBooleanv(GL_COLOR_WRITEMASK, colorMask);
            glDisable(GL_SCISSOR_TEST); glDisable(GL_FRAMEBUFFER_SRGB);
            glColorMaski(0, true, true, true, true);
        }
        @Override public void close() {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, read); glBindFramebuffer(GL_DRAW_FRAMEBUFFER, draw);
            glBindTexture(GL_TEXTURE_2D, texture); glBindRenderbuffer(GL_RENDERBUFFER, renderbuffer);
            set(GL_SCISSOR_TEST, scissor); set(GL_FRAMEBUFFER_SRGB, srgb);
            glColorMaski(0, colorMask.get(0)!=0, colorMask.get(1)!=0, colorMask.get(2)!=0, colorMask.get(3)!=0);
        }
        private static void set(int state, boolean enabled) { if (enabled) glEnable(state); else glDisable(state); }
    }
}
