package com.nstut.simplymirrors.client;

/**
 * Optional Oculus shader-program metadata, implemented by a scoped mixin.
 * This is a plain interface: target classes are not instances of mixin
 * implementation classes at runtime.
 */
public interface ShaderProgramLabel {
    void simplymirrors$setProgramLabel(String name);
}
