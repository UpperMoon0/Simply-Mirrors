package com.nstut.simplymirrors.math;

import java.util.*;

/**
 * Backward region propagation over versioned pass outputs, rather than
 * attachment names (which can alias after Iris ping-pong flips).
 * A dependency with FULL footprint forces its producer and its ancestors
 * to full-frame. Missing/invalid graphs are never eligible for cropping.
 */
public final class ShaderRegionPlan {
    public static final int FULL = -1;
    private static final int UNUSED = -2;
    public static final class Input {
        final String producer;
        final int halo;
        public Input(String producer, int halo) {
            if (producer == null || halo < FULL) throw new IllegalArgumentException("input");
            this.producer = producer;
            this.halo = halo;
        }
    }
    public static final class Pass {
        final String name;
        final boolean fullOutput;
        final List<Input> inputs;
        public Pass(String name, boolean fullOutput, Input... inputs) {
            if (name == null || name.isEmpty()) throw new IllegalArgumentException("pass");
            this.name = name;
            this.fullOutput = fullOutput;
            this.inputs = Collections.unmodifiableList(Arrays.asList(inputs.clone()));
        }
    }
    private final Map<String,Integer> padding;
    private ShaderRegionPlan(Map<String,Integer> padding) {
        this.padding = Collections.unmodifiableMap(new HashMap<>(padding));
    }
    public int padding(String program) {
        if (program == null) return FULL;
        Integer value = padding.get(program.toLowerCase(Locale.ROOT));
        return value == null || value < 0 ? FULL : value;
    }

    /** Inputs must refer to an earlier pass. Unknown edges, cycles and duplicate
     * outputs invalidate the entire plan, not just one draw. */
    public static ShaderRegionPlan build(List<Pass> passes, String visibleOutput) {
        Map<String,Integer> result = new LinkedHashMap<>();
        for (Pass pass : passes) {
            if (result.containsKey(pass.name)) return invalid();
            for (Input input : pass.inputs)
                if (input == null || !result.containsKey(input.producer)) return invalid();
            result.put(pass.name, pass.fullOutput ? FULL : UNUSED);
        }
        if (!result.containsKey(visibleOutput)) return invalid();
        if (result.get(visibleOutput) != FULL) result.put(visibleOutput, 0);
        for (int i = passes.size()-1; i >= 0; i--) {
            Pass pass = passes.get(i);
            int required = result.get(pass.name);
            if (required == UNUSED) {
                // An unobserved output may be persistent or used by a path not
                // represented by the graph. Keep it and its inputs unrestricted.
                required = FULL;
                result.put(pass.name, FULL);
            }
            for (Input input : pass.inputs) {
                int old = result.get(input.producer);
                long sum = (long) required + input.halo;
                int next = required == FULL || input.halo == FULL || sum > Integer.MAX_VALUE
                    ? FULL : (int)sum;
                result.put(input.producer, old == FULL || next == FULL ? FULL : Math.max(old,next));
            }
        }
        return new ShaderRegionPlan(result);
    }
    private static ShaderRegionPlan invalid() {
        return new ShaderRegionPlan(Collections.<String,Integer>emptyMap());
    }

    /**
     * Experimental contract for the unmodified r5.9.3 default Overworld
     * pipeline only. Keep bloom/mipmap/SSR producers full. FXAA's edge
     * walk is bounded by its 12-step quality table; 40 pixels includes
     * interpolation and derivative slack. TAA samples current color in
     * a local 3x3 neighborhood and reads the isolated previous history.
     * Include direct edges for optional disabled composite stages.
     * Coverage already includes >=96 pixels of geometric padding.
     */
    public static ShaderRegionPlan complementaryDefault() {
        return build(Arrays.asList(
            new Pass("composite", true),
            new Pass("composite1", true, new Input("composite", FULL)),
            new Pass("composite2", true, new Input("composite1", FULL)),
            new Pass("composite3", true, new Input("composite2", FULL)),
            new Pass("composite4", true, new Input("composite3", FULL)),
            new Pass("composite5", false, new Input("composite4", FULL)),
            new Pass("composite6", false, new Input("composite5", 4)),
            new Pass("composite7", false, new Input("composite6", 40),
                new Input("composite5", 40)),
            new Pass("final", false, new Input("composite7", 8),
                new Input("composite6", 8), new Input("composite5", 8))
        ), "final");
    }
}
