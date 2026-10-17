package com.nstut.simplymirrors.fabric;
import com.nstut.simplymirrors.SimplyMirrors;
import net.fabricmc.api.ModInitializer;
public final class SimplyMirrorsFabric implements ModInitializer {
    @Override public void onInitialize() { SimplyMirrors.initialize(); }
}
