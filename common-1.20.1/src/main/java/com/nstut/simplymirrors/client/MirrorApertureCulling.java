package com.nstut.simplymirrors.client;

import com.nstut.simplymirrors.math.MirrorApertureVolume;
import com.nstut.simplymirrors.math.MirrorSurface;
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
    private static long checked, rejected, cumulativeChecked, cumulativeRejected;
    private static long completed;

    private MirrorApertureCulling() {}

    static void begin(List<MirrorSurface> surfaces, Vec3 reflectedEye) {
        aperture = Boolean.getBoolean("simplymirrors.apertureCull")
                ? MirrorApertureVolume.from(surfaces, reflectedEye.x, reflectedEye.y, reflectedEye.z)
                : null;
        reflectionFrustum = null;
        checked = rejected = 0;
    }

    public static void track(Frustum frustum) {
        if (MirrorRenderer.redirect != null && aperture != null)
            reflectionFrustum = frustum;
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

    static void end() {
        if (aperture != null) {
            cumulativeChecked += checked;
            cumulativeRejected += rejected;
            if (++completed % 120 == 0) {
                System.out.println("SIMPLY_MIRRORS_APERTURE_CULL sectionsChecked=" + cumulativeChecked
                        + " sectionsCulled=" + cumulativeRejected);
                cumulativeChecked = cumulativeRejected = 0;
            }
        }
        aperture = null;
        reflectionFrustum = null;
    }
}
