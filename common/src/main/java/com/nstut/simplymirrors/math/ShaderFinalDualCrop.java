package com.nstut.simplymirrors.math;

/**
 * Exact 1:1 native-resolution crop for the pinned final shader.
 *
 * The final program clears its player-vertex SSBO only from the framebuffer
 * origin pixel. The renderer can draw the padded mirror region once and that
 * single origin pixel once with the SAME program and framebuffer state,
 * avoiding the wasteful bounding rectangle that joined both regions.
 *
 * Only the optional 1.20.1 native GPU experiment may enable this policy.
 * Unrecognized programs, scaled viewports, origin overlap and large regions
 * fail closed to the regular full-frame draw.
 */
public final class ShaderFinalDualCrop {
    private static final int HALO = 96;
    private ShaderFinalDualCrop() { }

    public static int[] rectangle(String program, int x, int y, int width, int height,
                                  int nativeWidth, int nativeHeight,
                                  int viewportX, int viewportY,
                                  int viewportWidth, int viewportHeight) {
        if (!ShaderFinalOriginCrop.isPinnedFinal(program) ||
                nativeWidth <= 0 || nativeHeight <= 0 ||
                viewportX != 0 || viewportY != 0 ||
                viewportWidth != nativeWidth || viewportHeight != nativeHeight ||
                x < 0 || y < 0 || width <= 0 || height <= 0 ||
                (long)x + width > nativeWidth || (long)y + height > nativeHeight)
            return null;
        int[] region = ShaderViewportCrop.rectangle(x,y,width,height,
                nativeWidth,nativeHeight,viewportX,viewportY,
                viewportWidth,viewportHeight,HALO);
        // If origin is inside the region we cannot claim exactly one origin
        // fragment from the two-draw strategy. Preserve the full pass instead.
        if (region == null || (region[0] == 0 && region[1] == 0))
            return null;
        return region;
    }
}
