package com.nstut.simplymirrors.client;
import com.mojang.math.Matrix4f;
import org.lwjgl.BufferUtils;
import java.nio.FloatBuffer;
/** Minecraft 1.16's matrix adapter, remapped with the rest of the client. */
final class ShaderMatrices {
    static Object copy(Object value) {return value==null?null:new Matrix4f((Matrix4f)value);}
    static float[] array(Object value) {
        if(value==null)return null;
        FloatBuffer buffer=BufferUtils.createFloatBuffer(16);((Matrix4f)value).store(buffer);
        float[] result=new float[16];for(int i=0;i<16;i++)result[i]=buffer.get(i);return result;
    }
}
