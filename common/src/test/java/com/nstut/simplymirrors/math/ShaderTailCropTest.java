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
}
