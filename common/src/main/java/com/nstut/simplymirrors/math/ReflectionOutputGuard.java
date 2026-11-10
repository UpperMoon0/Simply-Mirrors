package com.nstut.simplymirrors.math;

/**
 * The only safe target for mirror-coverage scissoring is the original
 * native-resolution reflection color attachment. Offscreen, scaled, deferred,
 * shadow and history targets must never be clipped.
 */
public final class ReflectionOutputGuard {
    private ReflectionOutputGuard() { }

    public static boolean matches(int reflectionColorTexture, int boundColorTexture,
                                  int nativeWidth, int nativeHeight,
                                  int viewportX, int viewportY,
                                  int viewportWidth, int viewportHeight) {
        return reflectionColorTexture > 0 &&
               reflectionColorTexture == boundColorTexture &&
               nativeWidth > 0 && nativeHeight > 0 &&
               viewportX == 0 && viewportY == 0 &&
               viewportWidth == nativeWidth && viewportHeight == nativeHeight;
    }
}
