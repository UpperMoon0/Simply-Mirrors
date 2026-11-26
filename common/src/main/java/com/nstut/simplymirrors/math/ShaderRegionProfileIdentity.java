package com.nstut.simplymirrors.math;

/** Iris/Oculus uses a NamespacedId, whose diagnostic toString is not a resource ID. */
public final class ShaderRegionProfileIdentity {
    private ShaderRegionProfileIdentity() {}

    /** Never accept an unknown dimension API or a display string. */
    public static boolean isOverworld(Object dimension) throws ReflectiveOperationException {
        if (dimension == null) return false;
        Class<?> type = dimension.getClass();
        Object namespace = type.getMethod("getNamespace").invoke(dimension);
        Object name;
        try {
            name = type.getMethod("getName").invoke(dimension);
        } catch (NoSuchMethodException absent) {
            // Some Iris versions expose Minecraft's ResourceLocation instead.
            name = type.getMethod("getPath").invoke(dimension);
        }
        return "minecraft".equals(namespace) && "overworld".equals(name);
    }
}
