package com.nstut.simplymirrors.math;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Opt-in, full-resolution final-fragment rejection for the pinned
 * Complementary Reimagined r5.9.3 final shader only.
 *
 * The original final draw still covers the whole viewport; each fragment
 * outside the generously padded mirror ROI terminates at the shader entry.
 * The origin fragment must execute to preserve the pack's player SSBO reset.
 * Other shader packs, shader stages, and source variants fail closed.
 */
public final class ShaderFinalFragmentGuard {
    private static final String UNIFORM = "simplymirrors_FinalFragmentRect";
    private static final Pattern MAIN = Pattern.compile(
            "\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)\\s*\\{");
    // Iris's AST printer is allowed to reformat GLSL whitespace. Match
    // semantics conservatively without requiring the exact source layout.
    private static final Pattern ORIGIN = Pattern.compile(
            "\\bgl_FragCoord\\s*\\.\\s*x\\s*\\+\\s*gl_FragCoord\\s*\\.\\s*y\\s*<\\s*1\\.5[fF]?\\b");
    private static final Pattern CLEAR = Pattern.compile(
            "\\bclearSSBOs\\s*\\(\\s*\\)\\s*;");
    private static final Pattern COLOR = Pattern.compile(
            "\\b(?:gl_FragData\\s*\\[\\s*0\\s*\\]|iris_FragData0)\\s*(?:=(?!=)|\\+=|-=|\\*=|/=)");

    private ShaderFinalFragmentGuard() {}

    public static <S extends Enum<S>> Map<S,String> transformProgram(String name, Map<S,String> stages) {
        if (!"final".equals(name) || stages == null) return stages;
        for (Map.Entry<S,String> entry : stages.entrySet()) {
            if (!"FRAGMENT".equals(entry.getKey().name()) || entry.getValue() == null) continue;
            String source = transformFragment(entry.getValue());
            if (source == entry.getValue()) return stages;
            Map<S,String> updated = new LinkedHashMap<>(stages);
            updated.put(entry.getKey(), source);
            return updated;
        }
        return stages;
    }

    // Match the actual function body, including nested conditional blocks.
    // The source has already had comments masked by the caller.
    private static int matchingBrace(String source, int open) {
        if (open < 0 || open >= source.length() || source.charAt(open) != '{') return -1;
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        return -1;
    }
    public static String transformFragment(String source) {
        String masked = ShaderClippingTransformation.maskComments(source);
        if (masked.contains(UNIFORM)) return source;
        // Oculus rewrites gl_FragData[0] to iris_FragData0 before this hook.
        // HIGH also preprocesses away clearSSBOs entirely: world-space player
        // reflections are disabled by that preset. Do not require an absent
        // reset routine, but admit that path only for the pinned pure final
        // shader and reject every shared-memory resource / side-effect form.
        if (!COLOR.matcher(masked).find()) return source;
        boolean hasReset = CLEAR.matcher(masked).find();
        if (hasReset) {
            if (!ORIGIN.matcher(masked).find()) return source;
            Matcher clearFunction = Pattern.compile(
                    "\\bvoid\\s+clearSSBOs\\s*\\(\\s*\\)\\s*\\{").matcher(masked);
            if (!clearFunction.find()) return source;
            int clearStart = clearFunction.end();
            int clearEnd = matchingBrace(masked, clearStart - 1);
            if (clearEnd < 0 || !ORIGIN.matcher(masked.substring(clearStart, clearEnd)).find()) return source;
        } else {
            // These are distinctive expressions in Reimagined r5.9.3 final.
            // All reachable functions are checked, not just main: a helper
            // with image/SSBO/atomic writes must never be skipped off-screen.
            if (!Pattern.compile("\\bvec2\\s+texCoordM\\s*=\\s*texCoord\\s*;").matcher(masked).find() ||
                    !Pattern.compile("\\btexture(?:2D)?\\s*\\(\\s*colortex3\\s*,\\s*texCoordM\\s*\\)").matcher(masked).find() ||
                    !Pattern.compile("\\bcolor\\s*\\+=\\s*vec3\\s*\\(\\s*\\(\\s*dither\\s*-\\s*0\\.25[fF]?\\s*\\)\\s*/\\s*128(?:\\.0)?[fF]?\\s*\\)\\s*;").matcher(masked).find() ||
                    Pattern.compile("\\b(?:clearSSBOs|buffer|shared|coherent|volatile|restrict|atomic\\w*|image\\w*|[iu]image\\w*|[iu]samplerBuffer|samplerBuffer|memoryBarrier\\w*|barrier)\\b").matcher(masked).find())
                return source;
        }
        Matcher main = MAIN.matcher(masked);
        if (!main.find()) return source;
        int mainStart = main.start();
        int mainEnd = main.end();
        if (main.find()) return source;
        int mainClose = matchingBrace(masked, mainEnd - 1);
        Matcher colorMatcher = COLOR.matcher(masked);
        int colorWrite = colorMatcher.find(mainEnd) ? colorMatcher.start() : -1;
        if (mainClose < 0 || colorWrite < 0 || colorWrite >= mainClose) return source;
        if (hasReset) {
            // The origin reset must run in this entry point before color output.
            Matcher resetInvocation = CLEAR.matcher(masked);
            int resetCall = resetInvocation.find(mainEnd) ? resetInvocation.start() : -1;
            // A reset in a later helper is not evidence that main invokes it.
            if (mainClose < 0 || resetCall >= mainClose) return source;
            // Multiple reset invocations imply additional per-fragment side effects.
            // A single origin-preserving fast path cannot prove those safe.
            if (resetCall >= 0 && resetCall < mainClose && CLEAR.matcher(masked.substring(resetCall + 1, mainClose)).find()) return source;
            if (resetCall < 0 || resetCall >= mainClose || colorWrite < 0 || colorWrite >= mainClose || resetCall > colorWrite) return source;
            // A conditional or loop-local reset is not equivalent to the pinned
            // shader's unconditional reset. Never authorize fragment rejection
            // based on a call hidden inside nested control flow.
            int depth = 0;
            for (int i = mainEnd; i < resetCall; i++) {
                char c = masked.charAt(i);
                if (c == '{') depth++;
                else if (c == '}') depth--;
                if (depth < 0) return source;
            }
            if (depth != 0) return source;
            // Braceless control flow also makes a reset conditional despite brace depth zero.
            if (Pattern.compile("\\b(?:if|for|while|switch)\\s*\\([^)]*\\)\\s*(?:[^\\s{]|$)").matcher(masked.substring(mainEnd, resetCall)).find()) return source;
            // Early exits before the SSBO reset make the origin-side effect conditional.
            // Reject such variants rather than relying on shader-specific control flow.
            if (Pattern.compile("\\b(?:return|discard)\\s*;").matcher(masked.substring(mainEnd, resetCall)).find()) return source;
        }
        String entry = "\nuniform vec4 " + UNIFORM + ";\n";
        String guard = "\n    // A zero-width rect disables the optimization for the main camera.\n"
                + "    if (" + UNIFORM + ".z > " + UNIFORM + ".x &&\n"
                + "            (gl_FragCoord.x < " + UNIFORM + ".x || gl_FragCoord.y < " + UNIFORM + ".y ||\n"
                + "             gl_FragCoord.x >= " + UNIFORM + ".z || gl_FragCoord.y >= " + UNIFORM + ".w) &&\n"
                + "            gl_FragCoord.x + gl_FragCoord.y >= 1.5) discard;\n";
        return source.substring(0, mainStart) + entry
                + source.substring(mainStart, mainEnd) + guard
                + source.substring(mainEnd);
    }
}