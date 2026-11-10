package com.nstut.simplymirrors.math;

import org.junit.Test;
import static org.junit.Assert.*;

public class ShaderTailCropTest {
    @Test public void onlyWhitelistedLocalTailStagesCanCrop() {
        assertEquals(-1, ShaderTailCrop.paddingForProgram(null));
        for (String pass : new String[]{"begin","deferred1","composite","composite1",
                "composite3","composite4","shadow","unknown","final","final_extra"}) {
            assertEquals(pass, -1, ShaderTailCrop.paddingForProgram(pass));
        }
        assertEquals(128, ShaderTailCrop.paddingForProgram("composite5"));
        assertEquals(96, ShaderTailCrop.paddingForProgram("COMPOSITE6"));
        assertEquals(48, ShaderTailCrop.paddingForProgram("composite7"));

    }

    @Test public void padShrinksTowardsOutputButNeverDiscardsMirrorPixels() {
        int last = Integer.MAX_VALUE;
        for (String pass : new String[]{"composite5","composite6","composite7"}) {
            int pad = ShaderTailCrop.paddingForProgram(pass);
            assertTrue(pad > 0 && pad <= last);
            int[] rectangle = ShaderViewportCrop.rectangle(700,260,420,400,
                    1920,1080,0,0,1920,1080,pad);
            assertNotNull(rectangle);
            assertTrue(rectangle[0] <= 700 && rectangle[1] <= 260);
            assertTrue(rectangle[0] + rectangle[2] >= 1120);
            assertTrue(rectangle[1] + rectangle[3] >= 660);
            last = pad;
        }
    }

    @Test public void finalPassCropRetainsOriginFragmentAndRejectsWideOrInvalidBoxes() {
        int[] crop = ShaderViewportCrop.rectangle(700, 260, 420, 360,
                1920, 1080, 0, 0, 1920, 1080, 32);
        assertNotNull(crop);
        int[] expanded = ShaderTailCrop.includeOrigin(crop, 1920, 1080);
        assertNotNull(expanded);
        assertEquals(0, expanded[0]);
        assertEquals(0, expanded[1]);
        assertEquals(crop[0] + crop[2], expanded[2]);
        assertEquals(crop[1] + crop[3], expanded[3]);
        assertTrue(expanded[2] <= 1920 && expanded[3] <= 1080);

        // Never fake a win by clipping a nearly full-screen final draw.
        assertNull(ShaderTailCrop.includeOrigin(new int[]{0, 0, 1920, 1000}, 1920, 1080));
        assertNull(ShaderTailCrop.includeOrigin(new int[]{-1, 4, 16, 16}, 1920, 1080));
        assertNull(ShaderTailCrop.includeOrigin(new int[]{1600, 100, 500, 200}, 1920, 1080));
        assertNull(ShaderTailCrop.includeOrigin(new int[]{0, 0, 0, 1}, 1920, 1080));
        assertNull(ShaderTailCrop.includeOrigin(null, 1920, 1080));
    }

}
