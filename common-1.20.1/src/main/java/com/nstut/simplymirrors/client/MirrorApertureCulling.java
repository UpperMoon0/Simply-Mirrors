package com.nstut.simplymirrors.client;

import com.nstut.simplymirrors.math.MirrorApertureVolume;
import com.nstut.simplymirrors.math.MirrorSurface;
import com.nstut.simplymirrors.client.mixin.ReflectionFrustumIntersectionAccessor;
import org.joml.FrustumIntersection;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.List;

/**
 * Only the LevelRenderer's reflected-camera frustum is eligible for aperture
 * culling. Shader shadow frusta and the normal camera are never tagged.
 *
 * Opt in with -Dsimplymirrors.apertureCull=true until native parity passes.
 */
public final class MirrorApertureCulling {
    private static MirrorApertureVolume aperture;
    private static Frustum reflectionFrustum;
    private static FrustumIntersection reflectionIntersection;
    private static Vec3 reflectionEye;
    private static long checked, rejected, cumulativeChecked, cumulativeRejected;
    private static long sodiumChecked, sodiumRejected, cumulativeSodiumChecked, cumulativeSodiumRejected;
    private static long completed;

    private MirrorApertureCulling() {}

    static void begin(List<MirrorSurface> surfaces, Vec3 reflectedEye) {
        aperture = Boolean.getBoolean("simplymirrors.apertureCull")
                ? MirrorApertureVolume.from(surfaces, reflectedEye.x, reflectedEye.y, reflectedEye.z)
                : null;
        reflectionFrustum = null;
        reflectionIntersection = null;
        reflectionEye = aperture == null ? null : reflectedEye;
        checked = rejected = sodiumChecked = sodiumRejected = 0;
    }

    public static void track(Frustum frustum, Vec3 eye) {
        if (MirrorRenderer.redirect != null && aperture != null
                && reflectionEye != null && eye.distanceToSqr(reflectionEye) < 1.0e-6) {
            reflectionFrustum = frustum;
            // Embeddium wraps this SAME intersection in SimpleFrustum, rather
            // than calling vanilla Frustum.isVisible for chunk sections.
            reflectionIntersection =
                    ((ReflectionFrustumIntersectionAccessor) frustum).simplymirrors$getIntersection();
        }
    }

    public static boolean mayBeVisible(Frustum frustum, AABB bounds) {
        if (MirrorRenderer.redirect == null || frustum != reflectionFrustum || aperture == null)
            return true;
        checked++;
        boolean visible = aperture.intersects(bounds.minX, bounds.minY, bounds.minZ,
                bounds.maxX, bounds.maxY, bounds.maxZ);
        if (!visible) rejected++;
        return visible;
    }

    /**
     * Embeddium 0.3.31 calls SimpleFrustum.testAab with camera-relative boxes.
     * Identity-match its JOML intersection against the tagged reflected camera.
     * Shader shadow cameras must never inherit the mirror aperture.
     */
    public static boolean mayBeVisibleEmbeddium(FrustumIntersection intersection,
                                                 float minX, float minY, float minZ,
                                                 float maxX, float maxY, float maxZ) {
        if (MirrorRenderer.redirect == null || aperture == null
                || reflectionEye == null || intersection != reflectionIntersection)
            return true;
        checked++;
        sodiumChecked++;
        boolean visible = aperture.intersects(
                minX + reflectionEye.x, minY + reflectionEye.y, minZ + reflectionEye.z,
                maxX + reflectionEye.x, maxY + reflectionEye.y, maxZ + reflectionEye.z);
        if (!visible) { rejected++; sodiumRejected++; }
        return visible;
    }

    static void end() {
        if (aperture != null) {
            cumulativeChecked += checked;
            cumulativeRejected += rejected;
            cumulativeSodiumChecked += sodiumChecked;
            cumulativeSodiumRejected += sodiumRejected;
            if (++completed % 120 == 0) {
                System.out.println("SIMPLY_MIRRORS_APERTURE_CULL sectionsChecked=" + cumulativeChecked
                        + " sectionsCulled=" + cumulativeRejected
                        + " sodiumSectionsChecked=" + cumulativeSodiumChecked
                        + " sodiumSectionsCulled=" + cumulativeSodiumRejected);
                cumulativeChecked = cumulativeRejected = 0;
                cumulativeSodiumChecked = cumulativeSodiumRejected = 0;
            }
        }
        aperture = null;
        reflectionFrustum = null;
        reflectionIntersection = null;
        reflectionEye = null;
    }
}
