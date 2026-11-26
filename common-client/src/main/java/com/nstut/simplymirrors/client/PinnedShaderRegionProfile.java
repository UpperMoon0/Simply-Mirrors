package com.nstut.simplymirrors.client;

import com.nstut.simplymirrors.math.ShaderRegionPlan;
import com.nstut.simplymirrors.math.ShaderRegionProfileIdentity;
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
    private static String lastRejection;
    private static boolean deny(String reason) {
        if (Boolean.getBoolean("simplymirrors.gpuProfile") && !reason.equals(lastRejection)) {
            System.out.println("SIMPLY_MIRRORS_DEPENDENCY_ROI_PROFILE accepted=false reason="+reason);
            lastRejection=reason;
        }
        return false;
    }
    private PinnedShaderRegionProfile() {}

    static boolean eligible() {
        try {
            if (iris == null) iris=Class.forName("net.irisshaders.iris.Iris",false,
                    PinnedShaderRegionProfile.class.getClassLoader());
            Object dimension=iris.getMethod("getCurrentDimension").invoke(null);
            if (!ShaderRegionProfileIdentity.isOverworld(dimension)) return deny("dimension");
            Optional<?> active=(Optional<?>)iris.getMethod("getCurrentPack").invoke(null);
            Object pack=active.orElse(null);
            if (pack == null) { checkedPack=null; accepted=false; return deny("no-pack"); }
            if (pack != checkedPack) {
                checkedPack=pack;
                accepted=false;
                String name=(String)iris.getMethod("getCurrentPackName").invoke(null);
                if (!PACK_NAME.equals(name)) return deny("pack-name");
                Object options=pack.getClass().getMethod("getShaderPackOptions").invoke(pack);
                Object values=options.getClass().getMethod("getOptionValues").invoke(options);
                int changed=((Number)values.getClass().getMethod("getOptionsChanged").invoke(values)).intValue();
                if (changed!=0) return deny("changed-options-"+changed);
                Path directory=(Path)iris.getMethod("getShaderpacksDirectory").invoke(null);
                Path archive=directory.resolve(PACK_NAME);
                if (!Files.isRegularFile(archive) || Files.size(archive)!=553397L) return deny("archive-size");
                byte[] digest=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(archive));
                StringBuilder hex=new StringBuilder(64);
                for(byte b:digest) hex.append(Character.forDigit((b>>>4)&15,16)).append(Character.forDigit(b&15,16));
                accepted=SHA256.equals(hex.toString());
                if (!accepted) return deny("archive-digest");
                lastRejection=null;
                if (Boolean.getBoolean("simplymirrors.gpuProfile"))
                    System.out.println("SIMPLY_MIRRORS_DEPENDENCY_ROI_PROFILE accepted=true");
            }
            return accepted;
        } catch (Exception | LinkageError unavailable) {
            accepted=false;
            return deny("api-"+unavailable.getClass().getSimpleName());
        }
    }

    static int padding(String program) { return PLAN.padding(program); }
}
