package com.nstut.simplymirrors.client;
import com.nstut.simplymirrors.math.MirrorMatrices;
import java.lang.reflect.Method;
import static org.lwjgl.opengl.GL33.*;
/** Optional shader state is render-thread scoped; no loader or shader dependency is required. */
public final class ShaderReflection {
    public static Object camera;
    private static float[] plane,projection;
    private static Method active;
    private static boolean searched;
    public static boolean enabled() {
        if (!searched) {
            searched=true;
            for(String name:new String[]{"net.irisshaders.iris.api.v0.IrisApi","net.coderbot.iris.api.v0.IrisApi"}) {
                try {active=Class.forName(name).getMethod("isShaderPackInUse");break;} catch(ReflectiveOperationException ignored) { }
            }
        }
        try {return active!=null && (Boolean)active.invoke(active.getDeclaringClass().getMethod("getInstance").invoke(null));}
        catch(ReflectiveOperationException e) {throw new IllegalStateException("Cannot query shader pipeline",e);}
    }
    public static void begin(float[] perspective,float[] view,double nx,double ny,double nz,double x,double y,double z,Object reflectedCamera) {
        plane=MirrorMatrices.clipPlane(view,nx,ny,nz,x,y,z);
        projection=perspective;camera=reflectedCamera;glEnable(GL_CLIP_DISTANCE0);
    }
    public static void end() {camera=null;plane=null;projection=null;glDisable(GL_CLIP_DISTANCE0);}
    public static void upload() {
        int program=glGetInteger(GL_CURRENT_PROGRAM);
        if(program==0)return;
        int location=glGetUniformLocation(program,"simplymirrors_ClipPlane");
        if(location<0)return;
        if(projection!=null){int matrix=glGetUniformLocation(program,"simplymirrors_Projection");if(matrix>=0)glUniformMatrix4fv(matrix,false,projection);}
        if(plane!=null && glIsEnabled(GL_CLIP_DISTANCE0))glUniform4f(location,plane[0],plane[1],plane[2],plane[3]);
        else glUniform4f(location,0,0,0,1);
    }
}
