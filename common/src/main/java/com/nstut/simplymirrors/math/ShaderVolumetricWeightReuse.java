package com.nstut.simplymirrors.math;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Opt-in algebraic reuse in Complementary r5.9.3's composite1 fog integrator.
 * The previous iteration already computed the current slice's starting weight.
 * Retain all samples, shadow lookups, break conditions and accumulation order.
 */
public final class ShaderVolumetricWeightReuse {
    private static final String CARRY = "simplymirrors_PreviousFogWeight";
    private ShaderVolumetricWeightReuse() {}
    public static <S extends Enum<S>> Map<S,String> transformProgram(String name, Map<S,String> stages) {
        if (!"composite1".equals(name) || stages == null) return stages;
        for (Map.Entry<S,String> e : stages.entrySet()) {
            if (!"FRAGMENT".equals(e.getKey().name()) || e.getValue() == null) continue;
            String updated = transformFragment(e.getValue());
            if (updated == e.getValue()) return stages;
            Map<S,String> copy = new LinkedHashMap<>(stages);
            copy.put(e.getKey(), updated);
            return copy;
        }
        return stages;
    }
    public static String transformFragment(String source) {
        String masked = ShaderClippingTransformation.maskComments(source);
        if (masked.contains(CARRY) || !masked.contains("GetVolumetricLight")) return source;
        Pattern initial = Pattern.compile("float\\s+lastDistance\\s*=\\s*0\\.0[fF]?\\s*;");
        Pattern weight = Pattern.compile("float\\s+currentWeight\\s*=\\s*smoothstep1\\s*\\(\\s*pow\\s*\\(\\s*lastDistance\\s*/\\s*maxDistance\\s*,\\s*fogCurve\\s*\\)\\s*\\)\\s*;");
        Pattern next = Pattern.compile("float\\s+nextWeight\\s*=\\s*smoothstep1\\s*\\(\\s*pow\\s*\\(\\s*nextDistance\\s*/\\s*maxDistance\\s*,\\s*fogCurve\\s*\\)\\s*\\)\\s*;");
        Pattern advance = Pattern.compile("\\blastDistance\\s*=\\s*nextDistance\\s*;");
        Matcher a=initial.matcher(masked), b=weight.matcher(masked), c=next.matcher(masked), d=advance.matcher(masked);
        if (!a.find() || !b.find() || !c.find() || !d.find() ||
                !(a.end()<b.start() && b.end()<=c.start() && c.end()<d.start())) return source;
        int ai=a.end(), bs=b.start(), be=b.end(), ds=d.start();
        if (a.find() || b.find() || c.find() || d.find()) return source;
        // These bounded, positive exponents make the initial weight exactly zero.
        // No approximation of pow(), sample count, or distance is introduced.
        return source.substring(0,ai) + "\nfloat " + CARRY + " = 0.0;\n"
            + source.substring(ai,bs) + "float currentWeight = " + CARRY + ";"
            + source.substring(be,ds) + CARRY + " = nextWeight;\n"
            + source.substring(ds);
    }
}
