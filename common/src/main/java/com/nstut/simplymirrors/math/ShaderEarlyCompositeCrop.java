package com.nstut.simplymirrors.math;

/**
 * Opt-in, pinned-shader experiment for the SSR-producing "composite" pass.
 *
 * Complementary Reimagined r5.9.3 uses REFLECTION_RES=0.5 by default:
 * colortex7 has a 1/2-scale viewport even though the mirror's final image
 * remains native-resolution. Map a conservatively padded native mirror
 * rectangle into exactly that existing viewport; never resize an image,
 * change shader options, or crop an unrecognized program or scale.
 *
 * Input textures and temporal history remain complete. Native GPU
 * frame-by-frame parity is mandatory before this experiment can ship.
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
                nativeWidth > 32768 || nativeHeight > 32768 ||
                viewportX != 0 || viewportY != 0 ||
                x < 0 || y < 0 || width <= 0 || height <= 0 ||
                (long)x + width > nativeWidth || (long)y + height > nativeHeight)
            return null;

        // The pinned pack supports REFLECTION_RES=0.5 or 1.0. Iris truncates
        // odd native dimensions in its half-size viewport (e.g. 1061 -> 530).
        // Other scales, offsets and mixed dimensions fail closed.
        boolean nativeScale = viewportWidth == nativeWidth &&
                              viewportHeight == nativeHeight;
        boolean halfScale = (long)viewportWidth * 2 >= nativeWidth - 1L &&
                            (long)viewportWidth * 2 <= nativeWidth &&
                            (long)viewportHeight * 2 >= nativeHeight - 1L &&
                            (long)viewportHeight * 2 <= nativeHeight;
        if (!nativeScale && !halfScale)
            return null;

        return ShaderViewportCrop.rectangle(x, y, width, height,
                nativeWidth, nativeHeight, viewportX, viewportY,
                viewportWidth, viewportHeight, PADDING);
    }
}
