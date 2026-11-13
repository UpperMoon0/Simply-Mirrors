package com.nstut.simplymirrors.client;

import java.util.ArrayDeque;
import java.util.Iterator;
import static org.lwjgl.opengl.GL33.*;

/**
 * Render-thread-only scratch storage for Iris/Oculus history snapshots.
 *
 * Allocating a renderbuffer for every history texture level on every reflected
 * frame forces GPU storage churn. A snapshot owns its scratch image until its
 * close() completes; only then may another pass reuse that exact format/size.
 * The bounded pool never stores the contents as shader history.
 */
final class GlHistoryBackupPool {
    private static final long MAX_CACHED_BYTES = 256L * 1024 * 1024;
    // Cap retained VRAM separately from active one-frame capture storage.
    private static final ArrayDeque<Buffer> FREE = new ArrayDeque<Buffer>();
    private static long cachedBytes;
    private static long allocations;
    private static long reuses;

    private GlHistoryBackupPool() { }

    static int acquire(int format, int width, int height) {
        for (Iterator<Buffer> it = FREE.descendingIterator(); it.hasNext();) {
            Buffer entry = it.next();
            if (entry.format == format && entry.width == width && entry.height == height) {
                it.remove();
                cachedBytes -= entry.bytes;
                reuses++;
                return entry.id;
            }
        }
        int id = glGenRenderbuffers();
        try {
            glBindRenderbuffer(GL_RENDERBUFFER, id);
            glRenderbufferStorage(GL_RENDERBUFFER, format, width, height);
        } catch (RuntimeException | Error failure) {
            glDeleteRenderbuffers(id);
            throw failure;
        }
        allocations++;
        return id;
    }

    static void release(int id, int format, int width, int height) {
        long bytes = (long) width * height * estimatedBytesPerPixel(format);
        if (bytes > MAX_CACHED_BYTES) {
            glDeleteRenderbuffers(id);
            return;
        }
        // Drop oldest free images first, retaining recently used mip/format pairs.
        while (!FREE.isEmpty() && cachedBytes + bytes > MAX_CACHED_BYTES) {
            Buffer discarded = FREE.removeFirst();
            cachedBytes -= discarded.bytes;
            glDeleteRenderbuffers(discarded.id);
        }
        FREE.addLast(new Buffer(id, format, width, height, bytes));
        cachedBytes += bytes;
    }

    private static int estimatedBytesPerPixel(int format) {
        // Storage formats are already queried from the actual Iris texture.
        // Unknown formats are charged at a conservative 16 bytes/pixel.
        switch (format) {
            case GL_RGBA32F: case GL_RGBA32I: case GL_RGBA32UI: return 16;
            case GL_RGB32F: case GL_RGB32I: case GL_RGB32UI: return 12;
            case GL_RG32F: case GL_RG32I: case GL_RG32UI:
            case GL_RGBA16F: case GL_RGBA16I: case GL_RGBA16UI: return 8;
            case GL_RGB16F: case GL_RGB16I: case GL_RGB16UI: return 6;
            case GL_RGBA8: case GL_RGBA8_SNORM: case GL_SRGB8_ALPHA8:
            case GL_RGB10_A2: case GL_R11F_G11F_B10F:
            case GL_R32F: case GL_R32I: case GL_R32UI:
            case GL_RG16F: case GL_RG16I: case GL_RG16UI: return 4;
            case GL_RGB8: case GL_RGB8_SNORM: case GL_SRGB8: return 3;
            case GL_RG8: case GL_RG8_SNORM:
            case GL_R16F: case GL_R16I: case GL_R16UI: return 2;
            case GL_R8: case GL_R8_SNORM: return 1;
            default: return 16;
        }
    }

    /** Must run on the active GL render thread before a context is discarded. */
    static void clear() {
        while (!FREE.isEmpty()) glDeleteRenderbuffers(FREE.removeFirst().id);
        cachedBytes = 0;
    }

    static long allocations() { return allocations; }
    static long reuses() { return reuses; }
    static long cachedBytes() { return cachedBytes; }

    private static final class Buffer {
        final int id, format, width, height;
        final long bytes;
        Buffer(int id, int format, int width, int height, long bytes) {
            this.id = id;
            this.format = format;
            this.width = width;
            this.height = height;
            this.bytes = bytes;
        }
    }
}
