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
    /** A requested dependency experiment must not silently overlap another crop. */
    public static void requireExclusiveExperiment(boolean requested, boolean... conflicting) {
        if (requested) for (boolean enabled : conflicting)
            if (enabled) throw new IllegalArgumentException("Dependency ROI cannot be combined with other shader crop or rewrite experiments");
    }

    private static ShaderRegionPlan invalid() {
        return new ShaderRegionPlan(Collections.<String,Integer>emptyMap());
    }

    /**
     * Only the terminal draw is eligible until the runtime attachment graph
     * can be attested before execution. composite5 feeds a mipmapped input;
     * composite6 writes persistent colortex2 TAA history; composite7 cannot
     * assume that the later final draw will pass its independent GL guard.
     * A full fallback therefore never consumes partially rendered inputs.
     */
    public static ShaderRegionPlan complementaryDefault() {
        return build(Arrays.asList(
            new Pass("composite", true),
            new Pass("composite1", true, new Input("composite", FULL)),
            new Pass("composite2", true, new Input("composite1", FULL)),
            new Pass("composite3", true, new Input("composite2", FULL)),
            new Pass("composite4", true, new Input("composite3", FULL)),
            new Pass("composite5", true, new Input("composite4", FULL)),
            new Pass("composite6", true, new Input("composite5", 4)),
            new Pass("composite7", true, new Input("composite6", 40),
                new Input("composite5", 40)),
            new Pass("final", false, new Input("composite7", 8),
                new Input("composite6", 8), new Input("composite5", 8))
        ), "final");
    }
}
