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
        optimized = constantGaussianWeights(optimized);
        int optimizedOpen=optimized.indexOf('{');
        optimized=optimized.substring(0,optimizedOpen+1)
            +"\nif (!"+UNIFORM+") return simplymirrors_MainReflectionBlur(centerCol,nViewPos,dither,z0);\n"
            +optimized.substring(optimizedOpen+1);
        String baseline=body.replaceFirst("sampleBlurFilteredReflection","simplymirrors_MainReflectionBlur");
        return source.substring(0,start)+"uniform bool "+UNIFORM+";\n"+baseline+"\n"+optimized+source.substring(end);
    }
    // Keep the compact loop: native #125's expanded kernel regressed composite1.
    // Nine constant radii cover dx*dx+dy*dy for the fixed 5x5 kernel. GLSL
    // evaluates these constant expressions at compile time, preserving exp's
    // input and each tap's accumulation order without a per-tap exponential.
    private static String constantGaussianWeights(String body) {
        String weight = "float weight = exp(-spatialDist2 / spatialFactorM);";
        String kernel = "int k = 2;";
        if (!body.contains(kernel) || !body.contains(weight)
                || !body.contains("const float spatialFactorM = 2.0 * spatialFactor * spatialFactor;")) return body;
        StringBuilder table = new StringBuilder("const float simplymirrors_BlurWeights[9] = float[9](");
        for (int i=0; i<=8; i++) {
            if (i>0) table.append(',');
            table.append("exp(-").append(i).append(".0 / spatialFactorM)");
        }
        table.append(");\n");
        return body.replace(kernel,table+kernel)
            .replace(weight,"float weight = simplymirrors_BlurWeights[int(spatialDist2)];");
    }
}
