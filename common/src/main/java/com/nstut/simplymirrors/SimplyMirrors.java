package com.nstut.simplymirrors;

/** Loader-neutral identity. Keep this class free of client-only and Minecraft classes. */
public final class SimplyMirrors {
    public static final String MOD_ID = "simply_mirrors";
    public static final String NAME = "Simply Mirrors";

    private SimplyMirrors() { }

    public static void initialize() {
        java.util.logging.Logger.getLogger(MOD_ID).info(NAME + " development bootstrap loaded");
    }
}
