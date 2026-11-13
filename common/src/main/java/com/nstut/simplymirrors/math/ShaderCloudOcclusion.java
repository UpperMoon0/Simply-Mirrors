package com.nstut.simplymirrors.math;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
/** Reject cloud rays entirely behind opaque terrain before their sampling loop.
 * Retains every visible sample and all screen-space consumers, including bloom.
 * Experimental and reflection-only until strict native acceptance succeeds.
 */
public final class ShaderCloudOcclusion {
    public static final String UNIFORM = "simplymirrors_CloudOcclusion";
    private ShaderCloudOcclusion() {}
    public static <S extends Enum<S>> Map<S,String> transformProgram(String name, Map<S,String> stages) {
        if (!"deferred1".equals(name) || stages == null) return stages;
        for (Map.Entry<S,String> e : stages.entrySet()) {
            if (!"FRAGMENT".equals(e.getKey().name()) || e.getValue() == null) continue;
            String patched = transformFragment(e.getValue());
            if (patched == e.getValue()) return stages;
            Map<S,String> copy = new LinkedHashMap<>(stages);
            copy.put(e.getKey(), patched); return copy;
        }
        return stages;
    }
    public static String transformFragment(String source) {
        String s = ShaderClippingTransformation.maskComments(source);
        if (s.contains(UNIFORM)) return source;
        // Require the pinned Reimagined cloud slab and monotonically advancing ray.
        if (!s.contains("GetVolumetricClouds") || !s.contains("cloudStretch") ||
            !Pattern.compile("tracePos\\.y\\s*-=\\s*traceAdd\\.y\\s*;").matcher(s).find() ||
            !Pattern.compile("tracePos\\s*\\+=\\s*traceAdd\\s*;").matcher(s).find()) return source;
        Matcher entry = Pattern.compile("vec4\\s+GetVolumetricClouds\\s*\\(").matcher(s);
        Matcher slab = Pattern.compile("float\\s+planeDistanceDif\\s*=\\s*maxPlaneDistance\\s*-\\s*minPlaneDistance\\s*;").matcher(s);
        if (!entry.find() || !slab.find()) return source;
        int start = entry.start(), insert = slab.end();
        if (start >= insert || entry.find() || slab.find()) return source;
        // Every original sample has length >= minPlaneDistance: all ray component
        // coefficients are nonnegative and increase, including the x/z first-step
        // offset. Leave a one-block/relative margin for floating-point rounding.
        // Exclude horizontal/nonfinite edge cases; original code remains fallback.
        String guard = "\nif (" + UNIFORM + " && skyFade < 0.7 && abs(nPlayerPos.y) > 0.00001"
            + " && minPlaneDistance < 100000000.0 && lViewPosM >= 0.0"
            + " && minPlaneDistance > lViewPosM + max(1.0, abs(lViewPosM) * 0.0001))"
            + " return vec4(0.0);\n";
        return source.substring(0,start) + "uniform bool " + UNIFORM + ";\n"
            + source.substring(start,insert) + guard + source.substring(insert);
    }
}
