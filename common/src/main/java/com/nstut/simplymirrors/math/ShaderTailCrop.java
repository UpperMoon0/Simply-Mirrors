package com.nstut.simplymirrors.math;

/**
 * Experimental, opt-in screen-space pass crop policy.
 *
 * The Complementary Reimagined r5.9.3 HIGH final chain first generates
 * full-frame bloom/DOF inputs, then consumes them through a small local tail.
 * Never crop the producers: a pixel inside the mirror can depend on pixels
 * outside it. Stage-specific padding is cumulative and intentionally wider
 * upstream. Other programs retain their unmodified full-screen draws.
 *
 * Program-name matching is only an experiment selector, not a general proof
 * that an arbitrary pack has bounded sample footprints. Default OFF.
 */
public final class ShaderTailCrop {
    private ShaderTailCrop() {}

    public static int paddingForProgram(String programName) {
        if (programName == null) return -1;
        switch (programName.toLowerCase(java.util.Locale.ROOT)) {
            case "composite5": return 128; // consume complete bloom atlas
            case "composite6": return 96;  // TAA neighbourhood and reprojection
            case "composite7": return 48;  // FXAA edge walk
            case "final": return 24;       // sharpen/chromatic aberration
            default: return -1;           // leave globally-dependent producers intact
        }
    }
}
