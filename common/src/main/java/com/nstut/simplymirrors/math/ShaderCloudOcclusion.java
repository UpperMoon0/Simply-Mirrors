package com.nstut.simplymirrors.math;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
/** End the cloud ray when remaining samples are behind opaque terrain.
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
        // The pinned ray advances monotonically away from the camera. Once a
        // sample is behind opaque terrain, every later sample is also hidden.
        // Stop that loop rather than repeating length tests for hidden samples.
        if (!s.contains("GetCloudNoise") || !s.contains("minPlaneDistance") ||
            !Pattern.compile("tracePos\\.y\\s*-=\\s*traceAdd\\.y\\s*;").matcher(s).find() ||
            !Pattern.compile("tracePos\\s*\\+=\\s*traceAdd\\s*;").matcher(s).find()) return source;
        Matcher entry=Pattern.compile("vec4\\s+GetVolumetricClouds\\s*\\(").matcher(s);
        Matcher hidden=Pattern.compile("if\\s*\\(\\s*lTracePos\\s*>\\s*lViewPosM\\s*\\)\\s*\\{\\s*if\\s*\\(\\s*skyFade\\s*<\\s*0\\.7f?\\s*\\)\\s*continue\\s*;").matcher(s);
        if(!entry.find() || !hidden.find())return source;
        int declaration=entry.start(), replaceStart=hidden.start(), replaceEnd=hidden.end();
        if(declaration>=replaceStart || entry.find() || hidden.find())return source;
        String replacement="if (lTracePos > lViewPosM) { if (skyFade < 0.7) {"
            +" if ("+UNIFORM+") break; else continue; }";
        return source.substring(0,declaration)+"uniform bool "+UNIFORM+";\n"
            +source.substring(declaration,replaceStart)+replacement+source.substring(replaceEnd);
    }
}
