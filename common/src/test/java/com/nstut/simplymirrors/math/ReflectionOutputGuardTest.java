package com.nstut.simplymirrors.math;

import org.junit.Test;
import static org.junit.Assert.*;

public class ReflectionOutputGuardTest {
    @Test public void onlyUnscaledSameColorAttachmentMayClip() {
        // FBO object identity is intentionally irrelevant: Iris colorHolder
        // reattaches the same reflection texture to a different FBO.
        assertTrue(ReflectionOutputGuard.matches(83, 83, 1920, 1080, 0, 0, 1920, 1080));
        assertFalse(ReflectionOutputGuard.matches(83, 98, 1920, 1080, 0, 0, 1920, 1080));
        assertFalse(ReflectionOutputGuard.matches(83, 0, 1920, 1080, 0, 0, 1920, 1080));
        assertFalse(ReflectionOutputGuard.matches(0, 0, 1920, 1080, 0, 0, 1920, 1080));
        assertFalse(ReflectionOutputGuard.matches(83, 83, 1920, 1080, 0, 0, 960, 540));
        assertFalse(ReflectionOutputGuard.matches(83, 83, 1920, 1080, 0, 0, 480, 270));
        assertFalse(ReflectionOutputGuard.matches(83, 83, 1920, 1080, 12, 0, 1920, 1080));
        assertFalse(ReflectionOutputGuard.matches(83, 83, 1920, 1080, 0, 4, 1920, 1080));
    }

    @Test public void invalidDimensionsCannotEnableScissor() {
        assertFalse(ReflectionOutputGuard.matches(83, 83, 0, 1080, 0, 0, 0, 1080));
        assertFalse(ReflectionOutputGuard.matches(83, 83, 1920, 0, 0, 0, 1920, 0));
    }
}
