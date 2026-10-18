package com.nstut.simplymirrors.client;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.shadows.ShadowRenderer;
import org.joml.Matrix4f;
import org.junit.Test;
import static org.junit.Assert.*;
public class IrisCameraStateTest {
    @Test public void firstSeenProgramUsesMainCameraWithoutLosingReflectedState() {
        CapturedRenderingState state=CapturedRenderingState.INSTANCE;
        Matrix4f main=new Matrix4f().translation(1,2,3);
        state.set(main,new Matrix4f());
        ShadowRenderer.MODELVIEW=new Matrix4f().translation(4,5,6);
        ShadowRenderer.PROJECTION=new Matrix4f();
        IrisCameraState saved=IrisCameraState.capture();
        // Newer Iris captures the mutable matrix by reference; preserve our own copy.
        main.translation(90,90,90);
        Matrix4f mirror=new Matrix4f().scaling(-1,1,1);
        state.set(mirror,new Matrix4f().scaling(2));
        Matrix4f mirrorShadow=new Matrix4f().translation(7,8,9);
        ShadowRenderer.MODELVIEW=mirrorShadow;
        saved.useMain();
        assertEquals(1,state.view().m30(),0);
        assertEquals(4,ShadowRenderer.MODELVIEW.m30(),0);
        saved.useReflected();
        assertSame(mirror,state.view());assertSame(mirrorShadow,ShadowRenderer.MODELVIEW);
        assertEquals(7,saved.shadowView()[12],0);
        saved.restore();
        assertEquals(1,state.view().m30(),0);
        assertEquals(4,ShadowRenderer.MODELVIEW.m30(),0);
    }
    @Test public void endRestoresMainEvenWhenUniformUpdateWasInterrupted() {
        CapturedRenderingState state=CapturedRenderingState.INSTANCE;
        state.set(new Matrix4f().translation(12,0,0),new Matrix4f());
        ShadowRenderer.MODELVIEW=new Matrix4f();ShadowRenderer.PROJECTION=new Matrix4f();
        IrisCameraState saved=IrisCameraState.capture();
        state.set(new Matrix4f().translation(-12,0,0),new Matrix4f());
        saved.useMain();saved.restore();
        assertEquals(12,state.view().m30(),0);
    }
}
