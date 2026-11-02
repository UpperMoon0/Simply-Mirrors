package com.nstut.simplymirrors.client;

import java.nio.ByteBuffer;
import static org.lwjgl.opengl.GL33.*;

/**
 * Match the secondary camera's depth storage to the active main camera.
 *
 * Iris temporarily re-attaches the Minecraft depth texture to its gbuffer FBOs.
 * A standalone TextureTarget may choose a different depth internal format from
 * the main target; some combinations make those Iris FBOs unsupported on OpenGL.
 * Only the mirror texture is modified, before it is cleared and rendered.
 */
public final class DepthTextureParity {
    private DepthTextureParity() { }

    public static boolean match(int sourceDepth, int mirrorDepth, int width, int height) {
        if (sourceDepth <= 0 || mirrorDepth <= 0 || sourceDepth == mirrorDepth) return false;
        int oldTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
        try {
            glBindTexture(GL_TEXTURE_2D, sourceDepth);
            int sourceFormat = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_INTERNAL_FORMAT);
            int sourceWidth = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH);
            int sourceHeight = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT);
            glBindTexture(GL_TEXTURE_2D, mirrorDepth);
            int mirrorFormat = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_INTERNAL_FORMAT);
            int mirrorWidth = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH);
            int mirrorHeight = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT);
            if (sourceWidth != width || sourceHeight != height
                    || mirrorWidth != width || mirrorHeight != height)
                throw new IllegalStateException("Mismatched mirror/main depth dimensions: "
                        + sourceWidth + "x" + sourceHeight + " vs "
                        + mirrorWidth + "x" + mirrorHeight + ", expected " + width + "x" + height);
            if (sourceFormat == mirrorFormat) return false;
            int dataType;
            switch (sourceFormat) {
                case GL_DEPTH_COMPONENT16: dataType = GL_UNSIGNED_SHORT; break;
                case GL_DEPTH_COMPONENT24:
                case GL_DEPTH_COMPONENT32: dataType = GL_UNSIGNED_INT; break;
                case GL_DEPTH_COMPONENT32F: dataType = GL_FLOAT; break;
                default:
                    // Do not guess a depth-stencil format or modify a texture whose
                    // OpenGL storage contract this adapter does not understand.
                    return false;
            }
            glTexImage2D(GL_TEXTURE_2D, 0, sourceFormat, width, height,
                    0, GL_DEPTH_COMPONENT, dataType, (ByteBuffer) null);
            int result = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_INTERNAL_FORMAT);
            if (result != sourceFormat)
                throw new IllegalStateException("Mirror depth allocation format drift: "
                        + sourceFormat + " -> " + result);
            return true;
        } finally {
            glBindTexture(GL_TEXTURE_2D, oldTexture);
        }
    }
}
