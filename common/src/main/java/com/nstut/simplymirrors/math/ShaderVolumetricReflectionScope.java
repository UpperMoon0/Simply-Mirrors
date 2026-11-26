package com.nstut.simplymirrors.math;

/** Keep the main-camera volumetric function unchanged during reflection A/B.
 * The renderer sets this uniform only around a reflected fullscreen draw.
 */
public final class ShaderVolumetricReflectionScope {
    public static final String UNIFORM = "simplymirrors_VolumetricReflection";
    private ShaderVolumetricReflectionScope() {}
    public static String scope(String original, String optimized) {
        if (original.equals(optimized) || optimized.contains(UNIFORM)) return optimized;
        String signature = "vec4 GetVolumetricLight(";
        int a = original.indexOf(signature), b = optimized.indexOf(signature);
        if (a < 0 || b < 0) return original;
        int ao = original.indexOf('{', a), bo = optimized.indexOf('{', b);
        if (ao < 0 || bo < 0) return original;
        int depth=1, end=ao+1;
        for (; end<original.length() && depth>0; end++) {
            char c=original.charAt(end);
            if (c=='{') depth++;
            else if (c=='}') depth--;
        }
        if (depth!=0) return original;
        String declaration=original.substring(a,ao);
        String parameters=declaration.substring(declaration.indexOf('(')+1,declaration.lastIndexOf(')'));
        StringBuilder arguments=new StringBuilder();
        for (String parameter:parameters.split(",")) {
            String[] words=parameter.trim().split("\\s+");
            if (words.length<2) return original;
            if (arguments.length()>0) arguments.append(',');
            arguments.append(words[words.length-1]);
        }
        String baseline=original.substring(a,end).replaceFirst("GetVolumetricLight","simplymirrors_MainVolumetricLight");
        return optimized.substring(0,b)+"uniform bool "+UNIFORM+";\n"+baseline+"\n"
            +optimized.substring(b,bo+1)+"\nif (!"+UNIFORM+") return simplymirrors_MainVolumetricLight("+arguments+");\n"
            +optimized.substring(bo+1);
    }
}
