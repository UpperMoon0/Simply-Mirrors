package com.nstut.simplymirrors.math;

/**
 * Opt-in, pinned-shader experiment for the early screen-space reflection pass.
 *
 * Complementary Reimagined r5.9.3's "composite" pass writes colortex7 SSR
 * results. Its inputs include full-frame textures and temporal history; those
 * sources MUST remain complete. This only restricts its output fragments to
 * the mirror region with a conservative 384-pixel producer halo.
 *
 * No guarantee is made for arbitrary packs or shader settings. This may only
 * ship after native full-resolution pixel parity and repeatable FPS acceptance.
 */
public final class ShaderEarlyCompositeCrop {
    private static final int PADDING = 384;

    private ShaderEarlyCompositeCrop() {}

    public static boolean isEligible(String program) {
        return "composite".equals(program);
    }

    public static int[] rectangle(String program, int x, int y, int width, int height,
                                  int nativeWidth, int nativeHeight,
                                  int viewportX, int viewportY,
                                  int viewportWidth, int viewportHeight) {
        if (!isEligible(program) || nativeWidth <= 0 || nativeHeight <= 0 ||
                viewportX != 0 || viewportY != 0 ||
                viewportWidth != nativeWidth || viewportHeight != nativeHeight ||
                x < 0 || y < 0 || width <= 0 || height <= 0 ||
                (long)x + width > nativeWidth || (long)y + height > nativeHeight)
            return null;
        return ShaderViewportCrop.rectangle(x, y, width, height,
                nativeWidth, nativeHeight, viewportX, viewportY,
                viewportWidth, viewportHeight, PADDING);
    }
}
