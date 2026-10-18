package com.nstut.simplymirrors.visualsupport;

/** Test-only use of Iris/Oculus' public API; no shader dependency in production. */
public final class ShaderProbe {
    private ShaderProbe() { }
    public static boolean active() {
        for (String name : new String[] {"net.irisshaders.iris.api.v0.IrisApi", "net.coderbot.iris.api.v0.IrisApi"}) {
            try {
                Class<?> api = Class.forName(name);
                Object instance = api.getMethod("getInstance").invoke(null);
                return Boolean.TRUE.equals(api.getMethod("isShaderPackInUse").invoke(instance));
            } catch (ClassNotFoundException absent) {
                // Try the pre-1.21 API package next.
            } catch (ReflectiveOperationException brokenApi) {
                throw new IllegalStateException("Could not verify active shader pack", brokenApi);
            }
        }
        return false;
    }
}
