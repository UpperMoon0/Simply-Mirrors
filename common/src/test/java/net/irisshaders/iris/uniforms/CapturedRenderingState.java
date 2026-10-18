package net.irisshaders.iris.uniforms;
import org.joml.Matrix4fc;
/** The optional bridge's pinned field contract; no Minecraft runtime is needed. */
public final class CapturedRenderingState {
    public static final CapturedRenderingState INSTANCE=new CapturedRenderingState();
    private Matrix4fc gbufferModelView, gbufferProjection;
    public void set(Matrix4fc view,Matrix4fc projection){gbufferModelView=view;gbufferProjection=projection;}
    public Matrix4fc view(){return gbufferModelView;}
    public Matrix4fc projection(){return gbufferProjection;}
}
