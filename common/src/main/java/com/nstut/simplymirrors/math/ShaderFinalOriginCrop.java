package com.nstut.simplymirrors.math;

/**
 * Opt-in crop for Complementary Reimagined r5.9.3's final fragment pass.
 *
 * In that pinned pack, final.glsl invokes clearSSBOs() only at
 * gl_FragCoord.xy == (0.5, 0.5). A conventional mirror-only rectangle loses
 * that write and silently corrupts subsequent temporal/player state.
 *
 * Instead include both the conservative padded mirror bounds and the lower
 * left framebuffer-origin pixel in ONE scissor rectangle. This never changes
 * render target resolution or skips a reflected frame. It deliberately gives
 * up cropping when preserving the origin would cover most of the screen.
 *
 * Do not use this policy for a shader pack that hasn't been pixel validated.
 */
public final class ShaderFinalOriginCrop {
    private ShaderFinalOriginCrop() { }

    public static int[] rectangle(int x, int y, int width, int height,
                                  int nativeWidth, int nativeHeight,
                                  int viewportX, int viewportY,
                                  int viewportWidth, int viewportHeight,
                                  int padding) {
        if (nativeWidth <= 0 || nativeHeight <= 0 ||
            viewportX != 0 || viewportY != 0 ||
            viewportWidth != nativeWidth || viewportHeight != nativeHeight ||
            width <= 0 || height <= 0 || padding < 0 ||
            x < 0 || y < 0) return null;

        // Fail closed for malformed/out-of-bounds rectangles and arithmetic
        // overflow; never turn an invalid coverage rectangle into a crop.
        long right = (long)x + width;
        long top = (long)y + height;
        if (right > nativeWidth || top > nativeHeight) return null;

        // The lower-left 1x1 pixel is always included, preserving the shader's
        // fragment-origin storage-buffer reset. The mirror remains fully
        // covered with padding for local sampling.
        long cropRight = Math.min(nativeWidth, right + padding);
        long cropTop = Math.min(nativeHeight, top + padding);
        if (cropRight <= 0 || cropTop <= 0 ||
            (double)cropRight * cropTop >=
                .82 * (double)nativeWidth * nativeHeight)
            return null;
        return new int[]{0, 0, (int)cropRight, (int)cropTop};
    }

    /** Strictly the known pinned pack's final program label. */
    public static boolean isPinnedFinal(String programName) {
        return "final".equalsIgnoreCase(programName);
    }
}
