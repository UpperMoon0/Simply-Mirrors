package com.nstut.simplymirrors.client.mixin;
import com.mojang.math.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(Matrix4f.class)
public interface MatrixAccessor {
    @Accessor("m00") void simplymirrors$m00(float value);
    @Accessor("m01") void simplymirrors$m01(float value);
    @Accessor("m02") void simplymirrors$m02(float value);
    @Accessor("m03") void simplymirrors$m03(float value);
    @Accessor("m10") void simplymirrors$m10(float value);
    @Accessor("m11") void simplymirrors$m11(float value);
    @Accessor("m12") void simplymirrors$m12(float value);
    @Accessor("m13") void simplymirrors$m13(float value);
    @Accessor("m20") void simplymirrors$m20(float value);
    @Accessor("m21") void simplymirrors$m21(float value);
    @Accessor("m22") void simplymirrors$m22(float value);
    @Accessor("m23") void simplymirrors$m23(float value);
    @Accessor("m30") void simplymirrors$m30(float value);
    @Accessor("m31") void simplymirrors$m31(float value);
    @Accessor("m32") void simplymirrors$m32(float value);
    @Accessor("m33") void simplymirrors$m33(float value);
}
