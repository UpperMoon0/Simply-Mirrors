package com.nstut.simplymirrors.math;

import org.junit.Test;
import static org.junit.Assert.*;

public class ShaderRegionProfileIdentityTest {
    public static final class NamespacedId {
        private final String namespace, name;
        NamespacedId(String namespace,String name) { this.namespace=namespace; this.name=name; }
        public String getNamespace() { return namespace; }
        public String getName() { return name; }
        @Override public String toString() {
            return "NamespacedId{namespace='"+namespace+"', name='"+name+"'}";
        }
    }
    public static final class ResourceLocation {
        public String getNamespace() { return "minecraft"; }
        public String getPath() { return "overworld"; }
    }
    public static final class Misleading {
        @Override public String toString() { return "minecraft:overworld"; }
    }
    @Test public void oculusDimensionUsesActualComponents() throws Exception {
        assertTrue(ShaderRegionProfileIdentity.isOverworld(new NamespacedId("minecraft","overworld")));
        assertFalse(ShaderRegionProfileIdentity.isOverworld(new NamespacedId("minecraft","the_nether")));
        assertFalse(ShaderRegionProfileIdentity.isOverworld(new NamespacedId("other","overworld")));
        assertFalse(ShaderRegionProfileIdentity.isOverworld(null));
        assertTrue(ShaderRegionProfileIdentity.isOverworld(new ResourceLocation()));
    }
    @Test(expected=NoSuchMethodException.class)
    public void displayStringCannotAuthorizeProfile() throws Exception {
        ShaderRegionProfileIdentity.isOverworld(new Misleading());
    }
}
