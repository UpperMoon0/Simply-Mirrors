package com.nstut.simplymirrors.math;

/**
 * Experimental, opt-in screen-space pass crop policy.
 *
 * The Complementary Reimagined r5.9.3 HIGH final chain first generates
 * full-frame bloom/DOF inputs, then consumes them through a small local tail.
 * Never crop the producers: a pixel inside the mirror can depend on pixels
 * outside it. Stage-specific padding is cumulative and intentionally wider
 * upstream. The final shader performs origin-dependent SSBO clearing and MUST
 * run uncropped by default. A separate opt-in may preserve the origin fragment.
 * Other programs retain their unmodified full-screen draws.
 *
 * Program-name matching is only an experiment selector, not a general proof
 * that an arbitrary pack has bounded sample footprints. Default OFF.
 */
public final class ShaderTailCrop {
    private ShaderTailCrop() {}


    /**
     * A final-pass fragment at (0,0) clears the player's vertex SSBO in the
     * pinned Complementary shader. A plain mirror rectangle can omit it.
     * Expand the native-viewport crop to include the origin, or fail closed
     * if that rectangle no longer saves meaningful raster work.
     *
     * Used only under the separate experimental shaderFinalCrop flag.
     */
    public static int[] includeOrigin(int[] box, int fullWidth, int fullHeight) {
        if (box == null || box.length != 4 ||
            fullWidth <= 0 || fullHeight <= 0 ||
            box[0] < 0 || box[1] < 0 || box[2] <= 0 || box[3] <= 0)
            return null;
        long right = (long)box[0] + box[2];
        long top = (long)box[1] + box[3];
        if (right > fullWidth || top > fullHeight ||
            right * top * 100L >= (long)fullWidth * fullHeight * 85L)
            return null;
        return new int[] {0, 0, (int)right, (int)top};
    }

    public static int paddingForProgram(String programName) {
        if (programName == null) return -1;
        switch (programName.toLowerCase(java.util.Locale.ROOT)) {
            case "composite5": return 128; // consume complete bloom atlas
            case "composite6": return 96;  // TAA neighbourhood and reprojection
            case "composite7": return 48;  // FXAA edge walk
            case "final": return -1;       // origin fragment clears player vertex SSBO
            default: return -1;           // leave globally-dependent producers intact
        }
    }
}
