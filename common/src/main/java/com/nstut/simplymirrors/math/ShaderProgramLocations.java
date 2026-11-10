package com.nstut.simplymirrors.math;

import java.util.HashMap;
import java.util.Map;

/**
 * Render-thread cache for active GL uniform locations.
 * OpenGL program names may be reused after shader reload, so callers MUST
 * clear this on each reflection transaction; never persist it across frames.
 * Negative (missing) locations are cached too.
 */
public final class ShaderProgramLocations {
    public interface Lookup {
        int find(int program, String name);
    }

    private final Map<Integer, Map<String, Integer>> byProgram =
            new HashMap<Integer, Map<String, Integer>>();

    public int get(int program, String name, Lookup lookup) {
        if (program == 0) return -1;
        Map<String, Integer> names = byProgram.get(program);
        if (names == null) {
            names = new HashMap<String, Integer>();
            byProgram.put(program, names);
        }
        Integer cached = names.get(name);
        if (cached != null) return cached.intValue();
        int found = lookup.find(program, name);
        names.put(name, Integer.valueOf(found));
        return found;
    }

    public void clear() {
        byProgram.clear();
    }
}
