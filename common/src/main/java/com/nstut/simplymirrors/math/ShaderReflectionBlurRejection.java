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
        // Enabled only when the draw's actual colortex7 sampler is non-mipmapped.
        // Sampling level zero then preserves texture2D's result without gradients
        // and removes the explicit gradient work suspected in native #120.
        String optimized=body.substring(0,color.start())
            +body.substring(color.end(),sum.start())
            +"vec4 sampleCol = textureLod(colortex7, sampleCoord, 0.0);\n"
            +body.substring(sum.start());
        optimized = unrollFixedTaps(optimized);
        int optimizedOpen=optimized.indexOf('{');
        optimized=optimized.substring(0,optimizedOpen+1)
            +"\nif (!"+UNIFORM+") return simplymirrors_MainReflectionBlur(centerCol,nViewPos,dither,z0);\n"
            +optimized.substring(optimizedOpen+1);
        String baseline=body.replaceFirst("sampleBlurFilteredReflection","simplymirrors_MainReflectionBlur");
        return source.substring(0,start)+"uniform bool "+UNIFORM+";\n"+baseline+"\n"+optimized+source.substring(end);
    }
    // Expand the pinned fixed kernel without changing sample order or count.
    // A single-iteration loop keeps each tap's continue local to that tap.
    private static String unrollFixedTaps(String body) {
        Matcher loops=Pattern.compile("for\\s*\\(int dy = -k; dy <= k; dy\\+\\+\\)\\s*\\{\\s*for\\s*\\(int dx = -k; dx <= k; dx\\+\\+\\)\\s*\\{").matcher(body);
        if (!body.contains("int k = 2;") || !loops.find()) return body;
        int start=loops.start(), inner=loops.end(), end=inner, depth=1;
        for (; end<body.length() && depth>0; end++) {
            char c=body.charAt(end);
            if(c=='{') depth++; else if(c=='}') depth--;
        }
        if(depth!=0) return body;
        String tap=body.substring(inner,end-1);
        int outer=end;
        while(outer<body.length() && Character.isWhitespace(body.charAt(outer))) outer++;
        if(outer>=body.length() || body.charAt(outer)!='}') return body;
        StringBuilder expanded=new StringBuilder("\n// simplymirrors fixed 25-tap expansion\n");
        for(int dy=-2;dy<=2;dy++) for(int dx=-2;dx<=2;dx++) {
            expanded.append("{ const int dy=").append(dy).append("; const int dx=").append(dx)
                .append("; do {\n").append(tap).append("\n} while(false); }\n");
        }
        return body.substring(0,start)+expanded+body.substring(outer+1);
    }

}
