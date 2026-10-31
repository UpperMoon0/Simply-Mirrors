package com.nstut.simplymirrors.visualtest;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

/**
 * TEMPORARY full-pack QA compatibility probe, NOT a production fix.
 *
 * Drippy 3.1.5 calls FancyMenu's ScreenCustomization from LoadingOverlay's
 * static initializer. On the isolated production NeoForge launcher, this
 * runs before FancyMenu 3.9.14 initialized its customizableScreens registry.
 * Only suppress the premature Drippy overlay customization call, while the
 * registry is still null. Keep both original mods and all renderer JARs.
 * Delete alongside all ATM10-only diagnostics before merging PR #6.
 */
@Pseudo
@Mixin(targets = "de.keksuccino.drippyloadingscreen.customization.DrippyOverlayScreen", remap = false)
public abstract class DrippyEarlyBootMixin {
    @Unique
    private static boolean simplymirrors$reported;

    @Inject(method = "forceEnableCustomizations", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void simplymirrors$guardEarlyOverlay(CallbackInfo ci) {
        if (!Boolean.getBoolean("simplymirrors.atm10Sky")) return;
        try {
            Class<?> type = Class.forName("de.keksuccino.fancymenu.customization.ScreenCustomization");
            Field registry = type.getDeclaredField("customizableScreens");
            registry.setAccessible(true);
            if (registry.get(null) == null) {
                if (!simplymirrors$reported) {
                    simplymirrors$reported = true;
                    System.out.println("ATM10_SKY_QA_DRIPPY_EARLY_OVERLAY_GUARD");
                }
                ci.cancel();
            }
        } catch (ReflectiveOperationException problem) {
            throw new IllegalStateException("ATM10 QA could not inspect FancyMenu startup registry", problem);
        }
    }
}
