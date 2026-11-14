package com.nstut.simplymirrors.math;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reject mismatched blur taps before fetching their unused reflection color. */
public final class ShaderReflectionBlurRejection {
    public static final String UNIFORM = "simplymirrors_ReflectionBlurRejection";
    private ShaderReflectionBlurRejection() {}
    public static <S extends Enum<S>> Map<S,String> transformProgram(String name, Map<S,String> stages) {
        if (!"composite1".equals(name) || stages == null) return stages;
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
        if (source.contains(UNIFORM)) return source;
        String masked=ShaderClippingTransformation.maskComments(source);
        Matcher entry=Pattern.compile("vec4\\s+sampleBlurFilteredReflection\\s*\\(").matcher(masked);
        if (!entry.find()) return source;
        int start=entry.start(), open=masked.indexOf('{',entry.end()), end=open+1, depth=1;
        if (open<0) return source;
        for (;end<masked.length() && depth>0;end++) {
            char c=masked.charAt(end);
            if(c=='{')depth++; else if(c=='}')depth--;
        }
        if(depth!=0)return source;
        String body=source.substring(start,end);
        Matcher color=Pattern.compile("vec4\\s+sampleCol\\s*=\\s*texture(?:2D)?\\s*\\(\\s*colortex7\\s*,\\s*sampleCoord\\s*\\)\\s*;").matcher(body);
        Matcher sum=Pattern.compile("sum\\s*\\+=\\s*sampleCol\\s*\\*\\s*weight\\s*;").matcher(body);
        if(!color.find() || !sum.find() || color.end()>=sum.start() || !body.contains("texture1Sample") || !body.contains("continue;"))return source;
        // Derivatives must be evaluated before divergent rejection, preserving
        // the original implicit LOD even where adjacent lanes reject a tap.
        String optimized=body.substring(0,color.start())
            +"vec2 simplymirrors_TapDx = dFdx(sampleCoord);\n"
            +"vec2 simplymirrors_TapDy = dFdy(sampleCoord);\n"
            +body.substring(color.end(),sum.start())
            +"vec4 sampleCol = textureGrad(colortex7, sampleCoord, simplymirrors_TapDx, simplymirrors_TapDy);\n"
            +body.substring(sum.start());
        int optimizedOpen=optimized.indexOf('{');
        optimized=optimized.substring(0,optimizedOpen+1)
            +"\nif (!"+UNIFORM+") return simplymirrors_MainReflectionBlur(centerCol,nViewPos,dither,z0);\n"
            +optimized.substring(optimizedOpen+1);
        String baseline=body.replaceFirst("sampleBlurFilteredReflection","simplymirrors_MainReflectionBlur");
        return source.substring(0,start)+"uniform bool "+UNIFORM+";\n"+baseline+"\n"+optimized+source.substring(end);
    }
}
