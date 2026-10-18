package com.nstut.simplymirrors.client;

import java.lang.reflect.Field;

/** Optional Iris/Oculus bridge; matrix types differ across Minecraft versions. */
final class IrisCameraState {
    private final Object state;
    private final Field view, projection;
    private final Object mainView, mainProjection;
    private final Field shadowView, shadowProjection;
    private final Object mainShadowView, mainShadowProjection;
    private Object reflectedShadowView, reflectedShadowProjection;
    private Object reflectedView, reflectedProjection;
    static IrisCameraState capture() {
        for (String prefix : new String[] {"net.irisshaders.iris", "net.coderbot.iris"}) {
            try {
                Class<?> type = Class.forName(prefix + ".uniforms.CapturedRenderingState");
                return new IrisCameraState(type);
            } catch (ClassNotFoundException absent) {
                // Try the older Iris / Oculus namespace.
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Cannot preserve Iris camera state", failure);
            }
        }
        throw new IllegalStateException("Active shader pipeline has no captured camera state");
    }
    private IrisCameraState(Class<?> type) throws ReflectiveOperationException {
        state = type.getField("INSTANCE").get(null);
        view = type.getDeclaredField("gbufferModelView");
        projection = type.getDeclaredField("gbufferProjection");
        view.setAccessible(true); projection.setAccessible(true);
        mainView = ShaderMatrices.copy(view.get(state)); mainProjection = ShaderMatrices.copy(projection.get(state));
        String prefix=type.getName().substring(0,type.getName().indexOf(".uniforms."));
        Class<?> shadows;
        try {shadows=Class.forName(prefix+".shadows.ShadowRenderer");}
        catch(ClassNotFoundException oldLayout){shadows=Class.forName(prefix+".pipeline.ShadowRenderer");}
        shadowView=shadows.getField("MODELVIEW"); shadowProjection=shadows.getField("PROJECTION");
        mainShadowView=ShaderMatrices.copy(shadowView.get(null)); mainShadowProjection=ShaderMatrices.copy(shadowProjection.get(null));
    }
    void useMain() {
        try {
            reflectedView = view.get(state); reflectedProjection = projection.get(state);
            reflectedShadowView=shadowView.get(null); reflectedShadowProjection=shadowProjection.get(null);
            restore();
        } catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
    }
    void useReflected() {
        set(reflectedView, reflectedProjection);
        setShadows(reflectedShadowView,reflectedShadowProjection);
    }
    void restore() { set(mainView, mainProjection); setShadows(mainShadowView,mainShadowProjection); }
    float[] shadowView() {return ShaderMatrices.array(reflectedShadowView);}
    float[] shadowProjection() {return ShaderMatrices.array(reflectedShadowProjection);}
    private void setShadows(Object modelView,Object perspective) {
        try {shadowView.set(null,modelView);shadowProjection.set(null,perspective);}
        catch(IllegalAccessException failure){throw new IllegalStateException(failure);}
    }
    private void set(Object modelView, Object perspective) {
        try { view.set(state, modelView); projection.set(state, perspective); }
        catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
    }
}
