package com.nstut.simplymirrors.math;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Experimental RGB-only volumetric integration shortcut. Preserve the
 * original ray traversal, global vlFactor, and every nonzero RGB sample.
 * Alpha may be omitted only when the preprocessed caller never reads it. */
public final class ShaderVolumetricZeroDensity {
    private static final String MARKER = "simplymirrors_ZeroDensity";
    private static final String ZERO = "0(?:\\.0*)?[fF]?";
    private static final Pattern DEFINITION = Pattern.compile("\\bvec4\\s+GetVolumetricLight\\s*\\(");
    private static final Pattern CALL = Pattern.compile("\\bvolumetricEffect\\s*=\\s*GetVolumetricLight\\s*\\(");
    private static final Pattern ALL_CALLS = Pattern.compile("\\bGetVolumetricLight\\s*\\(");
    private static final Pattern TAIL = Pattern.compile(
        "\\bfloat\\s+currentWeight\\s*=\\s*smoothstep1\\s*\\(\\s*pow\\s*\\(\\s*lastDistance\\s*/\\s*maxDistance\\s*,\\s*fogCurve\\s*\\)\\s*\\)\\s*;"
        +"\\s*float\\s+nextWeight\\s*=\\s*smoothstep1\\s*\\(\\s*pow\\s*\\(\\s*nextDistance\\s*/\\s*maxDistance\\s*,\\s*fogCurve\\s*\\)\\s*\\)\\s*;"
        +"\\s*float\\s+sliceWeight\\s*=\\s*nextWeight\\s*-\\s*currentWeight\\s*;"
        +"\\s*vec4\\s+stepResult\\s*=\\s*vec4\\s*\\(\\s*localDensity\\s*\\*\\s*sliceWeight\\s*,\\s*sliceWeight\\s*\\)\\s*;"
        +"\\s*if\\s*\\(\\s*nextDistance\\s*>\\s*lViewPos0\\s*\\)\\s*\\{"
        +"\\s*(?:if\\s*\\(\\s*nextDistance\\s*>\\s*entityLinearDepth\\s*\\)\\s*(?:\\{\\s*break\\s*;\\s*\\}|break\\s*;)\\s*)?"
        +"stepResult\\s*\\.\\s*rgb\\s*\\*=\\s*translucentMult\\s*;\\s*\\}"
        +"\\s*volumetricLight\\s*\\+=\\s*stepResult\\s*;"
        +"\\s*lastDistance\\s*=\\s*nextDistance\\s*;");
    private ShaderVolumetricZeroDensity() {}

    static boolean rgbOnlyCaller(String masked) {
        String rest=masked.replaceAll("\\bvec4\\s+volumetricEffect\\s*=\\s*vec4\\s*\\(\\s*"+ZERO+"\\s*\\)\\s*;","")
            .replaceAll("\\bvolumetricEffect\\s*=\\s*GetVolumetricLight\\s*\\(","GetVolumetricLight(")
            .replaceAll("\\bvolumetricEffect\\s*\\.\\s*rgb\\b","");
        if (Pattern.compile("\\bvolumetricEffect\\b").matcher(rest).find()) return false;
        Matcher calls=ALL_CALLS.matcher(masked);
        int count=0; while(calls.find()) count++;
        return count==2 && CALL.matcher(masked).find();
    }

    public static String transformFragment(String source) {
        String masked=ShaderClippingTransformation.maskComments(source);
        if(masked.contains(MARKER) || source.contains(MARKER) || !rgbOnlyCaller(masked)) return source;
        Matcher definition=DEFINITION.matcher(masked);
        if(!definition.find())return source;
        int open=masked.indexOf('{',definition.end()), end=open+1, depth=1;
        if(open<0)return source;
        for(;end<masked.length() && depth>0;end++){
            char ch=masked.charAt(end);
            if(ch=='{')depth++; else if(ch=='}')depth--;
        }
        if(depth!=0 || definition.find())return source;
        String function=masked.substring(open,end);
        if(function.contains("DrawEnderBeams") ||
            !Pattern.compile("\\bfor\\s*\\(\\s*int\\s+i\\s*=\\s*0\\s*;\\s*i\\s*<=\\s*totalSamples\\s*;").matcher(function).find())
            return source;
        String residual=function
            .replaceAll("\\bvec4\\s+volumetricLight\\s*=\\s*vec4\\s*\\(\\s*"+ZERO+"\\s*\\)\\s*;","")
            .replaceAll("\\bvolumetricLight\\s*\\+=\\s*stepResult\\s*;","")
            .replaceAll("\\bvolumetricLight\\s*\\.\\s*rgb\\b","")
            .replaceAll("\\bvolumetricLight\\s*=\\s*max\\s*\\(\\s*volumetricLight\\s*,\\s*vec4\\s*\\(\\s*"+ZERO+"\\s*\\)\\s*\\)\\s*;","")
            .replaceAll("\\breturn\\s+volumetricLight\\s*;","");
        if(Pattern.compile("\\bvolumetricLight\\b").matcher(residual).find())return source;
        Matcher tail=TAIL.matcher(function);
        if(!tail.find())return source;
        int at=open+tail.start(), after=open+tail.end();
        if(tail.find() || !masked.substring(after,end).trim().startsWith("}"))return source;
        // Ensure the matched tail is in the actual totalSamples loop, not a
        // neighboring scope. Its next closing brace must terminate that loop.
        Matcher loop=Pattern.compile("\\bfor\\s*\\(\\s*int\\s+i\\s*=\\s*0\\s*;\\s*i\\s*<=\\s*totalSamples\\s*;[^)]*\\)\\s*\\{").matcher(masked);
        if(!loop.find() || loop.end()>at)return source;
        int loopDepth=1, loopEnd=loop.end();
        for(;loopEnd<end && loopDepth>0;loopEnd++){
            char ch=masked.charAt(loopEnd);
            if(ch=='{')loopDepth++; else if(ch=='}')loopDepth--;
        }
        if(loopDepth!=0 || !masked.substring(after,loopEnd-1).trim().isEmpty())return source;
        int nested=0;
        for(int pos=loop.end();pos<at;pos++){
            char ch=masked.charAt(pos);
            if(ch=='{')nested++; else if(ch=='}')nested--;
        }
        if(nested!=0)return source;
        // No translucent multiplication or entity-break condition is omitted.
        // Invalid or nonfinite weight inputs fall back to original arithmetic.
        String guard="\n// "+MARKER+"\n"
            +"if (all(equal(localDensity,vec3(0.0))) && nextDistance <= lViewPos0"
            +" && maxDistance > 0.0 && !isinf(maxDistance)"
            +" && fogCurve > 0.0 && !isinf(fogCurve)"
            +" && lastDistance >= 0.0 && lastDistance <= maxDistance"
            +" && nextDistance >= 0.0 && nextDistance <= maxDistance) {\n"
            +"    lastDistance = nextDistance;\n    continue;\n}\n";
        return source.substring(0,at)+guard+source.substring(at);
    }
}
