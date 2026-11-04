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
    // A conservative bound (16 bytes/texel) also covers RGBA32F buffers.
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
        long bytes = (long) width * height * 16;
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
