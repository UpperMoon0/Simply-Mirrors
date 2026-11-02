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
    private static int diagnosticCount;
    private static final int MAX_DIAGNOSTICS = 24;

    boolean repairBoundDrawFramebuffer() {
        if (closed) throw new IllegalStateException("Reflection framebuffer scope is closed");
        int framebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        if (framebuffer == 0) return false;
        int initialStatus = glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER);
        if (initialStatus == GL_FRAMEBUFFER_COMPLETE) return false;
        // Iris can reattach a packed stencil when switching depth targets
        // during one reflection. Do not let a previously repaired FBO become
        // unsupported again before the next draw.

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
        // LAYERED=false does not imply a 2D texture: glFramebufferTextureLayer
        // attaches a *single* array/3D layer, including layer zero.
        int layer = stencilType == GL_TEXTURE
                ? glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER,
                    GL_STENCIL_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LAYER)
                : 0;
        int cubeFace = stencilType == GL_TEXTURE
                ? glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER,
                    GL_STENCIL_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_CUBE_MAP_FACE)
                : GL_NONE;
        boolean singleLayer = false;
        if (stencilType == GL_TEXTURE && layered == GL_FALSE && cubeFace == GL_NONE) {
            // Attachment queries do not distinguish a normal 2D image from
            // layer zero of an array texture. Temporarily attaching its full
            // image reveals whether the texture target itself is layered.
            // This is inside an already-incomplete reflection FBO; the saved
            // topology is restored on unsuccessful repair and on scope exit.
            glFramebufferTexture(GL_DRAW_FRAMEBUFFER, GL_STENCIL_ATTACHMENT,
                    stencilName, level);
            singleLayer = glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER,
                    GL_STENCIL_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_LAYERED) == GL_TRUE;
        }
        Stencil saved = new Stencil(stencilType, stencilName, level,
                layered == GL_TRUE, layer, cubeFace, singleLayer);
        detach();
        int detachedStatus = glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER);
        if (diagnosticCount < MAX_DIAGNOSTICS) {
            diagnosticCount++;
            System.err.println("SIMPLY_MIRRORS_REFLECTION_FBO_DIAGNOSTIC id=" + framebuffer
                    + " initial=" + initialStatus + " withoutStencil=" + detachedStatus
                    + " depthType=" + depthType + " depthName=" + depthName
                    + " stencilType=" + stencilType + " stencilName=" + stencilName);
        }
        if (detachedStatus == GL_FRAMEBUFFER_COMPLETE) {
            // The latest detached attachment is the only one we own. Iris may
            // have replaced an earlier attachment while this scope was active.
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
        } else if (attachment.cubeFace != GL_NONE) {
            glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_STENCIL_ATTACHMENT,
                    attachment.cubeFace, attachment.name, attachment.level);
        } else if (attachment.singleLayer) {
            glFramebufferTextureLayer(GL_DRAW_FRAMEBUFFER, GL_STENCIL_ATTACHMENT,
                    attachment.name, attachment.level, attachment.layer);
        } else {
            // Full layered texture or ordinary 2D texture: preserve the
            // original whole-image attachment rather than guessing a layer.
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
                // A non-empty stencil was installed by Iris after our detach.
                // It is newer state, and must never be overwritten by our snapshot.
                int currentType = glGetFramebufferAttachmentParameteri(
                        GL_DRAW_FRAMEBUFFER, GL_STENCIL_ATTACHMENT,
                        GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
                if (currentType == GL_NONE) restore(entry.getValue());
            }
        } finally {
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previousDraw);
            detached.clear();
        }
    }

    private static final class Stencil {
        final int type, name, level, layer, cubeFace;
        final boolean layered, singleLayer;
        Stencil(int type, int name, int level, boolean layered, int layer,
                int cubeFace, boolean singleLayer) {
            this.type = type;
            this.name = name;
            this.level = level;
            this.layered = layered;
            this.layer = layer;
            this.cubeFace = cubeFace;
            this.singleLayer = singleLayer;
        }
    }
}
