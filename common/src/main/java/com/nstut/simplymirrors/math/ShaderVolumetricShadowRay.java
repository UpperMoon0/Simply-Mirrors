package com.nstut.simplymirrors.math;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
/** Experimental affine shadow-ray factorization for pinned composite1.
 * Move the 3x3 shadow rotation outside the per-fragment sample loop.
 * Sampling, projection distortion and shadow comparisons remain unchanged.
 * Floating-point reassociation requires native pixel parity before use.
 */
public final class ShaderVolumetricShadowRay {
    private ShaderVolumetricShadowRay() {}
    public static <S extends Enum<S>> Map<S,String> transformProgram(String name,Map<S,String> stages) {
        if (!"composite1".equals(name)||stages==null) return stages;
        for(Map.Entry<S,String> e:stages.entrySet()) {
            if(!"FRAGMENT".equals(e.getKey().name())||e.getValue()==null)continue;
            String patched=transformFragment(e.getValue());
            if(patched==e.getValue())return stages;
            Map<S,String> copy=new LinkedHashMap<>(stages);copy.put(e.getKey(),patched);return copy;
        }
        return stages;
    }
    public static String transformFragment(String source) {
        String s=ShaderClippingTransformation.maskComments(source);
        if(s.contains("simplymirrors_ShadowRay"))return source;
        Matcher helper=Pattern.compile("vec3\\s+GetShadowPos\\s*\\(\\s*vec3\\s+playerPos\\s*\\)\\s*\\{([^{}]+)\\}").matcher(s);
        Matcher ray=Pattern.compile("vec3\\s+rayDirection\\s*=\\s*nViewPosInSceneSpace\\s*;").matcher(s);
        Matcher call=Pattern.compile("GetShadowPos\\s*\\(\\s*scenePos\\s*\\)").matcher(s);
        if(!helper.find()||!ray.find()||!call.find())return source;
        String body=source.substring(helper.start(1),helper.end(1));
        Pattern initial=Pattern.compile("vec3\\s+shadowPos\\s*=\\s*PlayerToShadow\\s*\\(\\s*playerPos\\s*\\)\\s*;");
        Matcher init=initial.matcher(body);
        if(!init.find()||!body.contains("shadowMapBias")||!body.contains("shadowPos.z"))return source;
        String newBody=init.replaceFirst(Matcher.quoteReplacement(
            "vec3 shadowPos = vec3(shadowProjection[0].x,shadowProjection[1].y,shadowProjection[2].z) * shadowViewPos + shadowProjection[3].xyz;"));
        String function="vec3 simplymirrors_ShadowRayPosition(vec3 shadowViewPos){"+newBody+"}\n";
        int hs=helper.start(), re=ray.end(), cs=call.start(), ce=call.end();
        if(!(hs<re&&re<cs)||helper.find()||ray.find()||call.find())return source;
        return source.substring(0,hs)+function+source.substring(hs,re)
            +"\nvec3 simplymirrors_ShadowRay = mat3(shadowModelView) * rayDirection;\n"
            +source.substring(re,cs)+"simplymirrors_ShadowRayPosition(simplymirrors_ShadowRay * nextDistance + shadowModelView[3].xyz)"
            +source.substring(ce);
    }
}
