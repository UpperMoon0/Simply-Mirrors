package com.nstut.simplymirrors.client;

import com.nstut.simplymirrors.math.ShaderRegionPlan;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Optional;

/** Fail-closed opt-in contract; never identify a shader solely by program name.
 * Cache only against the loaded ShaderPack instance, so reloads requalify.
 * The archive and default option set must match the reviewed configuration.
 */
final class PinnedShaderRegionProfile {
    private static final String SHA256 = "fed6c879e732cf7fc7bc6570ebb1def43235aa7e96f62859fbdbdbfa0b484b38";
    private static final String PACK_NAME = "ComplementaryReimagined_r5.9.3.zip";
    private static final ShaderRegionPlan PLAN = ShaderRegionPlan.complementaryDefault();
    private static Object checkedPack;
    private static boolean accepted;
    private static Class<?> iris;
    private PinnedShaderRegionProfile() {}

    static boolean eligible() {
        try {
            if (iris == null) iris=Class.forName("net.irisshaders.iris.Iris",false,
                    PinnedShaderRegionProfile.class.getClassLoader());
            Object dimension=iris.getMethod("getCurrentDimension").invoke(null);
            if (!"minecraft:overworld".equals(String.valueOf(dimension))) return false;
            Optional<?> active=(Optional<?>)iris.getMethod("getCurrentPack").invoke(null);
            Object pack=active.orElse(null);
            if (pack == null) { checkedPack=null; accepted=false; return false; }
            if (pack != checkedPack) {
                checkedPack=pack;
                accepted=false;
                String name=(String)iris.getMethod("getCurrentPackName").invoke(null);
                if (!PACK_NAME.equals(name)) return false;
                Object options=pack.getClass().getMethod("getShaderPackOptions").invoke(pack);
                Object values=options.getClass().getMethod("getOptionValues").invoke(options);
                if (((Number)values.getClass().getMethod("getOptionsChanged").invoke(values)).intValue()!=0)
                    return false;
                Path directory=(Path)iris.getMethod("getShaderpacksDirectory").invoke(null);
                Path archive=directory.resolve(PACK_NAME);
                if (!Files.isRegularFile(archive) || Files.size(archive)!=553397L) return false;
                byte[] digest=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(archive));
                StringBuilder hex=new StringBuilder(64);
                for(byte b:digest) hex.append(Character.forDigit((b>>>4)&15,16)).append(Character.forDigit(b&15,16));
                accepted=SHA256.equals(hex.toString());
                if (Boolean.getBoolean("simplymirrors.gpuProfile"))
                    System.out.println("SIMPLY_MIRRORS_DEPENDENCY_ROI_PROFILE accepted="+accepted);
            }
            return accepted;
        } catch (Exception | LinkageError unavailable) {
            accepted=false;
            return false;
        }
    }

    static int padding(String program) { return PLAN.padding(program); }
}
