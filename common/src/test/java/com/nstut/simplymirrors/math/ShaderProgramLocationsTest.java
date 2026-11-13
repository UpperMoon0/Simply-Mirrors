package com.nstut.simplymirrors.math;

import org.junit.Test;
import static org.junit.Assert.*;

public class ShaderProgramLocationsTest {
    @Test public void cachesPositiveAndMissingLocationsWithinOneReflection() {
        ShaderProgramLocations cache = new ShaderProgramLocations();
        final int[] calls = {0};
        ShaderProgramLocations.Lookup lookup = (program, name) -> {
            calls[0]++;
            return "missing".equals(name) ? -1 : program + name.length();
        };
        assertEquals(15, cache.get(10, "light", lookup));
        assertEquals(15, cache.get(10, "light", lookup));
        assertEquals(-1, cache.get(10, "missing", lookup));
        assertEquals(-1, cache.get(10, "missing", lookup));
        assertEquals(16, cache.get(11, "light", lookup));
        assertEquals(3, calls[0]);
    }

    @Test public void clearPreventsStaleProgramIdReuseAfterShaderReload() {
        ShaderProgramLocations cache = new ShaderProgramLocations();
        final int[] value = {4};
        ShaderProgramLocations.Lookup lookup = (program, name) -> value[0];
        assertEquals(4, cache.get(8, "gbufferModelView", lookup));
        value[0] = 9;
        assertEquals(4, cache.get(8, "gbufferModelView", lookup));
        cache.clear();
        assertEquals(9, cache.get(8, "gbufferModelView", lookup));
    }

    @Test public void unboundProgramNeverCallsDriver() {
        ShaderProgramLocations cache = new ShaderProgramLocations();
        assertEquals(-1, cache.get(0, "anything", (program, name) -> {
            throw new AssertionError("unbound program");
        }));
    }
}
