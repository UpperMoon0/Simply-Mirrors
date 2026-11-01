package com.nstut.simplymirrors.client;

import java.util.LinkedHashMap;
import java.util.Map;
import static org.lwjgl.opengl.GL33.*;

/**
 * Temporary stencil detachments for reflection-only Iris/Oculus framebuffers.
 *
 * Some packs install a packed depth-stencil attachment independently from
 * Minecraft's depth texture. During reflection Iris substitutes the mirror
 * depth texture, leaving a different stencil image attached. Mesa and several
 * drivers reject that combination with GL_FRAMEBUFFER_UNSUPPORTED (36061).
 *
 * Only modify an actually incomplete draw framebuffer, and only if removing
 * its independently attached stencil makes it complete. Restore the original
 * attachment on *every* exit, without changing a complete main-camera FBO.
 */
final class ReflectionFramebufferParity implements AutoCloseable {
    private final Map<Integer, Stencil> detached = new LinkedHashMap<>();
    private boolean closed;

    boolean repairBoundDrawFramebuffer() {
        if (closed) throw new IllegalStateException("Reflection framebuffer scope is closed");
        int framebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        if (framebuffer == 0 || detached.containsKey(framebuffer)
                || glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE)
            return false;

        int depthType = glGetFramebufferAttachmentParameteri(
                GL_DRAW_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        int stencilType = glGetFramebufferAttachmentParameteri(
                GL_DRAW_FRAMEBUFFER, GL_STENCIL_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        if (depthType == GL_NONE || (stencilType != GL_TEXTURE && stencilType != GL_RENDERBUFFER))
            return false;

        int depthName = glGetFramebufferAttachmentParameteri(
                GL_DRAW_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        int stencilName = glGetFramebufferAttachmentParameteri(
                GL_DRAW_FRAMEBUFFER, GL_STENCIL_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        if (depthType == stencilType && depthName == stencilName) return false;

        int level = stencilType == GL_TEXTURE
                ? glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER,
                    GL_STENCIL_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL)
                : 0;
        int layered = stencilType == GL_TEXTURE
                ? glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER,
                    GL_STENCIL_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_LAYERED)
                : GL_FALSE;
        int layer = (stencilType == GL_TEXTURE && layered == GL_TRUE)
                ? glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER,
                    GL_STENCIL_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LAYER)
                : 0;
        Stencil saved = new Stencil(stencilType, stencilName, level, layered == GL_TRUE, layer);
        detach();
        if (glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) {
            detached.put(framebuffer, saved);
            return true;
        }
        // The stencil is not the cause of the incomplete FBO. Never conceal
        // an unrelated attachment failure.
        restore(saved);
        return false;
    }

    private static void detach() {
        glFramebufferTexture(GL_DRAW_FRAMEBUFFER, GL_STENCIL_ATTACHMENT, 0, 0);
    }

    private static void restore(Stencil attachment) {
        if (attachment.type == GL_RENDERBUFFER) {
            glFramebufferRenderbuffer(GL_DRAW_FRAMEBUFFER,
                    GL_STENCIL_ATTACHMENT, GL_RENDERBUFFER, attachment.name);
        } else if (attachment.layered) {
            glFramebufferTexture(GL_DRAW_FRAMEBUFFER,
                    GL_STENCIL_ATTACHMENT, attachment.name, attachment.level);
        } else {
            // Iris's packed stencil image is a non-layered 2D texture.
            // glFramebufferTexture supports attaching without guessing target.
            glFramebufferTexture(GL_DRAW_FRAMEBUFFER,
                    GL_STENCIL_ATTACHMENT, attachment.name, attachment.level);
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        int previousDraw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        try {
            for (Map.Entry<Integer, Stencil> entry : detached.entrySet()) {
                if (!glIsFramebuffer(entry.getKey())) continue;
                glBindFramebuffer(GL_DRAW_FRAMEBUFFER, entry.getKey());
                restore(entry.getValue());
            }
        } finally {
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previousDraw);
            detached.clear();
        }
    }

    private static final class Stencil {
        final int type, name, level, layer;
        final boolean layered;
        Stencil(int type, int name, int level, boolean layered, int layer) {
            this.type = type;
            this.name = name;
            this.level = level;
            this.layered = layered;
            this.layer = layer;
        }
    }
}
