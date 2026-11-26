package com.nstut.simplymirrors.math;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Isolated center-sample reuse for the reviewed Complementary blur shape. */
public final class ShaderReflectionCenterReuse {
    public static final String UNIFORM = "simplymirrors_ReflectionCenterReuse";
    private ShaderReflectionCenterReuse() {}

    public static <S extends Enum<S>> Map<S,String> transformProgram(String name, Map<S,String> stages) {
        if (!"composite1".equals(name) || stages == null) return stages;
        for (Map.Entry<S,String> e : stages.entrySet()) {
            if (!"FRAGMENT".equals(e.getKey().name()) || e.getValue() == null) continue;
            String patched = transformFragment(e.getValue());
            if (patched == e.getValue()) return stages;
            Map<S,String> copy = new LinkedHashMap<>(stages);
            copy.put(e.getKey(), patched);
            return copy;
        }
        return stages;
    }

    // Match tokens rather than formatting: Iris may rewrite texture2D to texture.
    private static final String TEX = "texture(?:2D)?\\s*";
    private static final Pattern HEADER = Pattern.compile(
        "vec4\\s+sampleBlurFilteredReflection\\s*\\(\\s*vec4\\s+centerCol\\s*,\\s*vec3\\s+nViewPos\\s*,\\s*float\\s+dither\\s*,\\s*float\\s+z0\\s*\\)\\s*\\{");
    private static final Pattern NORMAL = Pattern.compile("vec4\\s+texture4\\s*=\\s*"+TEX+"\\(\\s*colortex4\\s*,\\s*texCoord\\s*\\)\\s*;");
    private static final Pattern COLOR = Pattern.compile("vec4\\s+sampleCol\\s*=\\s*"+TEX+"\\(\\s*colortex7\\s*,\\s*sampleCoord\\s*\\)\\s*;");
    private static final Pattern CENTER = Pattern.compile("vec4\\s+compositeReflection\\s*=\\s*("+TEX+"\\(\\s*colortex7\\s*,\\s*texCoord\\s*\\))\\s*;");
    private static final Pattern FRESNEL = Pattern.compile("float\\s+fresnelM\\s*=\\s*pow2\\s*\\(\\s*("+TEX+"\\(\\s*colortex4\\s*,\\s*texCoord\\s*\\))\\s*\\.a\\s*\\)\\s*;");
    private static final Pattern CALL = Pattern.compile("sampleBlurFilteredReflection\\s*\\(\\s*compositeReflection\\s*,\\s*nViewPos\\s*,\\s*dither\\s*,\\s*z0\\s*\\)");
    private static final Pattern LOOP = Pattern.compile("int\\s+k\\s*=\\s*2\\s*;\\s*for\\s*\\(\\s*int\\s+dy\\s*=\\s*-k\\s*;\\s*dy\\s*<=\\s*k\\s*;\\s*dy\\+\\+\\s*\\)\\s*\\{\\s*for\\s*\\(\\s*int\\s+dx\\s*=\\s*-k\\s*;\\s*dx\\s*<=\\s*k\\s*;\\s*dx\\+\\+\\s*\\)\\s*\\{\\s*vec2\\s+offset\\s*=\\s*vec2\\s*\\(\\s*float\\s*\\(\\s*dx\\s*\\)\\s*,\\s*float\\s*\\(\\s*dy\\s*\\)\\s*\\)\\s*\\*\\s*texelSize\\s*;\\s*vec2\\s+sampleCoord\\s*=\\s*texCoord\\s*\\+\\s*offset\\s*;");
    private static boolean unique(Matcher m) {
        return m.find() && !m.find() && m.reset().find();
    }
    public static String transformFragment(String source) {
        if (source.contains(UNIFORM) || source.contains(ShaderReflectionBlurRejection.UNIFORM)) return source;
        String masked = ShaderClippingTransformation.maskComments(source);
        Matcher header = HEADER.matcher(masked);
        if (!unique(header)) return source;
        int start=header.start(), end=header.end(), depth=1;
        for (;end<masked.length() && depth>0;end++) {
            char c=masked.charAt(end);
            if(c=='{') depth++; else if(c=='}') depth--;
        }
        if(depth!=0) return source;
        String body=source.substring(start,end), bodyMask=masked.substring(start,end);
        // centerCol must be unused in the original helper; caller may replace the
        // compositeReflection value while probing neighboring WSR samples.
        if(Pattern.compile("\\bcenterCol\\b").matcher(bodyMask.substring(header.end()-start)).find()) return source;
        Matcher normal=NORMAL.matcher(bodyMask), color=COLOR.matcher(bodyMask);
        if(!unique(normal) || !unique(color) || !LOOP.matcher(bodyMask).find()
                || normal.end()>=color.start()) return source;
        String tail=source.substring(end), tailMask=masked.substring(end);
        Matcher center=CENTER.matcher(tailMask), fresnel=FRESNEL.matcher(tailMask), call=CALL.matcher(tailMask);
        if(!unique(center)||!unique(fresnel)||!unique(call)
                || center.end()>=fresnel.start() || fresnel.end()>=call.start()) return source;
        // Keep original helper intact. Only the opt-in reflected branch calls the
        // duplicate, using the untouched center before WSR neighbor substitution.
        // Runtime requires non-mipmapped matching min/mag filters on BOTH samplers.
        String optimized=body.substring(0,normal.start())+"vec4 texture4 = simplymirrors_CenterNormal;\n"
            +body.substring(normal.end(),color.start())
            +"vec4 sampleCol = (dx == 0 && dy == 0) ? centerCol : "
            +body.substring(color.start(),color.end()).replaceFirst("^vec4\\s+sampleCol\\s*=\\s*","")
            +body.substring(color.end());
        optimized=optimized.replaceFirst("sampleBlurFilteredReflection\\s*\\(","simplymirrors_CenterBlur(")
            .replaceFirst("float\\s+z0\\s*\\)","float z0, vec4 simplymirrors_CenterNormal)");
        String changed=tail.substring(0,center.start())
            +"vec4 simplymirrors_RawCenter = "+tail.substring(center.start(1),center.end(1))+";\n"
            +"vec4 compositeReflection = simplymirrors_RawCenter;"
            +tail.substring(center.end(),fresnel.start())
            +"vec4 simplymirrors_CenterNormal = "+tail.substring(fresnel.start(1),fresnel.end(1))+";\n"
            +"float fresnelM = pow2(simplymirrors_CenterNormal.a);"
            +tail.substring(fresnel.end(),call.start())
            +"("+UNIFORM+" ? simplymirrors_CenterBlur(simplymirrors_RawCenter, nViewPos, dither, z0, simplymirrors_CenterNormal) : "
            +tail.substring(call.start(),call.end())+")"
            +tail.substring(call.end());
        return source.substring(0,start)+"uniform bool "+UNIFORM+";\n"+body+"\n"+optimized+changed;
    }
}
