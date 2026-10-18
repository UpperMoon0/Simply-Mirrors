package com.nstut.simplymirrors.client;
import com.nstut.simplymirrors.math.MirrorMatrices;
import com.nstut.simplymirrors.math.CameraUniformOverrides;
import java.lang.reflect.Method;
import static org.lwjgl.opengl.GL33.*;
/** Optional shader state is render-thread scoped; no loader or shader dependency is required. */
public final class ShaderReflection {
    public static Object camera;
    private static float[] plane,projection;
    private static int suspensions;
    private static CameraUniformOverrides cameraUniforms;
    private static IrisCameraState capturedCamera;
    private static ShaderRenderTargetSnapshot renderTargets;
    private static Object updatingCamera;
    private static final CameraUniformOverrides.Uniforms UNIFORMS = new CameraUniformOverrides.Uniforms() {
        public int currentProgram() { return glGetInteger(GL_CURRENT_PROGRAM); }
        public void bind(int program) { glUseProgram(program); }
        public float[] readFloats(int program, String name, int size) {
            int location=glGetUniformLocation(program,name); if(location<0)return null;
            float[] value=new float[size]; glGetUniformfv(program,location,value); return value;
        }
        public int[] readInts(int program, String name, int size) {
            int location=glGetUniformLocation(program,name); if(location<0)return null;
            int[] value=new int[size]; glGetUniformiv(program,location,value); return value;
        }
        public void writeFloats(int program, String name, float[] value) {
            int location=glGetUniformLocation(program,name);
            if(value.length==16)glUniformMatrix4fv(location,false,value);
            else if(value.length==3)glUniform3f(location,value[0],value[1],value[2]);
            else glUniform1f(location,value[0]);
        }
        public void writeInts(int program, String name, int[] value) {
            glUniform3i(glGetUniformLocation(program,name),value[0],value[1],value[2]);
        }
    };
    private static Method active;
    private static boolean searched;
    public static boolean terrainRendererPresent() {
        try {Class.forName("net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer",false,ShaderReflection.class.getClassLoader());return true;}
        catch(ClassNotFoundException absent){return false;}
    }
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
    public static void begin(float[] perspective,float[] view,double nx,double ny,double nz,double x,double y,double z,Object reflectedCamera,float[] mainView,double[] mainEye,double[] reflectedEye) {
        capturedCamera=IrisCameraState.capture();
        cameraUniforms=new CameraUniformOverrides(UNIFORMS,perspective,mainView,view,mainEye,reflectedEye);
        renderTargets=ShaderRenderTargetSnapshot.capture();
        plane=MirrorMatrices.clipPlane(view,nx,ny,nz,x,y,z);
        projection=MirrorMatrices.inverse(perspective);camera=reflectedCamera;suspensions=0;glEnable(GL_CLIP_DISTANCE0);
    }
    public static void end() {
        try { if(cameraUniforms!=null)cameraUniforms.restore(); }
        finally {
            try { if(capturedCamera!=null)capturedCamera.restore(); }
            finally {
                try {if(renderTargets!=null)renderTargets.close();}
                finally {renderTargets=null;cameraUniforms=null;capturedCamera=null;updatingCamera=null;camera=null;plane=null;projection=null;suspensions=0;glDisable(GL_CLIP_DISTANCE0);}
            }
        }
    }
    public static boolean reflecting() { return cameraUniforms!=null; }
    public static void beforeUniforms() {
        if(capturedCamera==null)return;
        // A program first seen in a reflection must initialize previous-frame suppliers
        // from the real camera too. Do not advance Iris's frame counter or suppliers.
        capturedCamera.useMain(); updatingCamera=camera; camera=null;
    }
    public static void afterUniforms() {
        if(capturedCamera!=null){camera=updatingCamera;updatingCamera=null;capturedCamera.useReflected();cameraUniforms.shadows(capturedCamera.shadowView(),capturedCamera.shadowProjection());cameraUniforms.upload();}
        upload();
    }
    public static boolean suspend() {
        boolean enabled=glIsEnabled(GL_CLIP_DISTANCE0);
        suspensions++;glDisable(GL_CLIP_DISTANCE0);return enabled;
    }
    public static void resume(boolean enabled) {
        if(suspensions>0)suspensions--;
        if(enabled)glEnable(GL_CLIP_DISTANCE0);else glDisable(GL_CLIP_DISTANCE0);
    }
    public static void upload() {
        int program=glGetInteger(GL_CURRENT_PROGRAM);
        if(program==0)return;
        int location=glGetUniformLocation(program,"simplymirrors_ClipPlane");
        // Unpatched sky/fallback programs do not define gl_ClipDistance.
        // Re-enable it when a transformed world program is bound again.
        boolean clip=plane!=null && suspensions==0 && location>=0;
        if(plane!=null){if(clip)glEnable(GL_CLIP_DISTANCE0);else glDisable(GL_CLIP_DISTANCE0);}
        if(location<0)return;
        if(projection!=null){int matrix=glGetUniformLocation(program,"simplymirrors_InverseProjection");if(matrix>=0)glUniformMatrix4fv(matrix,false,projection);}
        if(clip)glUniform4f(location,plane[0],plane[1],plane[2],plane[3]);
        else glUniform4f(location,0,0,0,1);
    }
}
