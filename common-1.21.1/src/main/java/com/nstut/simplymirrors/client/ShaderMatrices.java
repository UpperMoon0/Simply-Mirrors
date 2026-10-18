package com.nstut.simplymirrors.client;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
/** JOML matrix adapter shared by the version's two loaders. */
final class ShaderMatrices {
    static Object copy(Object value) {return value==null?null:new Matrix4f((Matrix4fc)value);}
    static float[] array(Object value) {return value==null?null:((Matrix4fc)value).get(new float[16]);}
}
