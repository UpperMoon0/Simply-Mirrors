package com.nstut.simplymirrors.client;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Isolates Iris/Oculus color history without creating a second world pipeline. */
final class ShaderRenderTargetSnapshot implements AutoCloseable {
    private final GlTextureSnapshot textures;
    private final List<Flag> flags;
    private final boolean[] shadowFlips, savedShadowFlips;
    private boolean closed;

    private ShaderRenderTargetSnapshot(GlTextureSnapshot textures, List<Flag> flags, boolean[] shadowFlips) {
        this.textures=textures; this.flags=flags; this.shadowFlips=shadowFlips;
        this.savedShadowFlips=shadowFlips==null?null:shadowFlips.clone();
    }

    static ShaderRenderTargetSnapshot capture() {
        try {
            Class<?> iris;
            try { iris=Class.forName("net.irisshaders.iris.Iris"); }
            catch (ClassNotFoundException legacy) { iris=Class.forName("net.coderbot.iris.Iris"); }
            Object manager=iris.getMethod("getPipelineManager").invoke(null);
            Object pipeline=manager.getClass().getMethod("getPipelineNullable").invoke(manager);
            return capture(pipeline);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot isolate shader render targets", failure);
        }
    }

    // Also used by the native regression with the same API-shaped pipeline fixtures.
    static ShaderRenderTargetSnapshot capture(Object pipeline) throws ReflectiveOperationException {
        if (pipeline == null) throw new IllegalStateException("No active shader pipeline");
        Object targets=field(pipeline.getClass(), "renderTargets").get(pipeline);
        Object directives=field(pipeline.getClass(), "packDirectives").get(pipeline);
        Object targetDirectives=directives.getClass().getMethod("getRenderTargetDirectives").invoke(directives);
        Map<?,?> settings=(Map<?,?>)targetDirectives.getClass().getMethod("getRenderTargetSettings").invoke(targetDirectives);
        List<Integer> ids=new ArrayList<>();
        List<Flag> flags=new ArrayList<>();
        for (Map.Entry<?,?> entry : settings.entrySet()) {
            Object setting=entry.getValue();
            if ((Boolean)setting.getClass().getMethod("shouldClear").invoke(setting)) continue;
            Object target=targets.getClass().getMethod("get", int.class).invoke(targets, ((Number)entry.getKey()).intValue());
            if (target == null) continue; // Unused buffers are not allocated by modern Iris.
            addTarget(target, ids, flags);
        }
        Object shadows=field(pipeline.getClass(), "shadowRenderTargets").get(pipeline);
        boolean[] shadowFlips=null;
        if (shadows!=null) {
            // Shadow composites have their own persistent color history and mutable flips.
            shadowFlips=(boolean[])field(shadows.getClass(), "flipped").get(shadows);
            Collection<?> cleared=(Collection<?>)shadows.getClass().getMethod("getBuffersToBeCleared").invoke(shadows);
            int count=(Integer)shadows.getClass().getMethod("getRenderTargetCount").invoke(shadows);
            for (int i=0; i<count; i++) {
                if (cleared.contains(i)) continue;
                Object target=shadows.getClass().getMethod("get", int.class).invoke(shadows, i);
                if (target!=null) addTarget(target, ids, flags);
            }
        }
        int[] textureIds=new int[ids.size()];
        for (int i=0; i<textureIds.length; i++) textureIds[i]=ids.get(i);
        // Pinned Iris/Oculus flips are immutable construction-time sets. Final swap
        // passes COPY between fixed main/alt IDs; both images must be restored.
        return new ShaderRenderTargetSnapshot(GlTextureSnapshot.capture(textureIds), flags, shadowFlips);
    }

    private static void addTarget(Object target, List<Integer> ids, List<Flag> flags) throws ReflectiveOperationException {
        ids.add((Integer)target.getClass().getMethod("getMainTexture").invoke(target));
        ids.add((Integer)target.getClass().getMethod("getAltTexture").invoke(target));
        // 1.11 uses sampler objects plus these flags; older versions only change filters.
        saveFlag(target, "mipmapsOnMain", flags);
        saveFlag(target, "mipmapsOnAlt", flags);
    }

    private static void saveFlag(Object owner, String name, List<Flag> flags) throws IllegalAccessException {
        try { Field field=field(owner.getClass(), name); flags.add(new Flag(owner, field, field.getBoolean(owner))); }
        catch (NoSuchFieldException olderIris) { /* No sampler-state flags before Iris 1.11. */ }
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current=type; current!=null; current=current.getSuperclass()) {
            try { Field field=current.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException absent) { /* Search the legacy pipeline's superclass. */ }
        }
        throw new NoSuchFieldException(type.getName()+"."+name);
    }

    @Override public void close() {
        if (closed) return;
        closed=true;
        try { textures.close(); }
        finally {
            if (shadowFlips!=null) System.arraycopy(savedShadowFlips,0,shadowFlips,0,shadowFlips.length);
            for (Flag flag : flags) {
                try { flag.field.setBoolean(flag.owner, flag.value); }
                catch (IllegalAccessException failure) { throw new IllegalStateException("Cannot restore shader sampler state", failure); }
            }
        }
    }

    private static final class Flag {
        final Object owner;
        final Field field;
        final boolean value;
        Flag(Object owner, Field field, boolean value) { this.owner=owner; this.field=field; this.value=value; }
    }
}
