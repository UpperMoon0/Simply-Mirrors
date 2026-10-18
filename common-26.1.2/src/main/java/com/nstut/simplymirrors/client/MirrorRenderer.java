package com.nstut.simplymirrors.client;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.*;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.RenderSystem;
import com.nstut.simplymirrors.block.*;
import com.nstut.simplymirrors.math.*;
import com.nstut.simplymirrors.client.mixin.*;
import net.minecraft.client.*;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.state.level.*;
import net.minecraft.core.*;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import java.util.*;
import static org.lwjgl.opengl.GL33.*;

/** Planar reflections on the 26.1.2 extracted-state renderer. OpenGL compositor, no portal runtime. */
public final class MirrorRenderer {
    public static RenderTarget redirect;
    public static long completedPasses;
    public static final Matrix4f mainProjection=new Matrix4f();
    private static TextureTarget target;
    private static ProjectionMatrixBuffer projectionBuffer;
    private static final GlMirrorComposite COMPOSITE=new GlMirrorComposite();
    private static Level lastLevel;
    private static List<MirrorSurface> cached=new ArrayList<>();
    private static int frame,compositeFbo;
    private static boolean failed;
    private MirrorRenderer() { }

    public static void render(GraphicsResourceAllocator allocator,DeltaTracker delta,CameraRenderState camera,
            Matrix4fc mainViewMatrix,GpuBufferSlice fog,Vector4f color,boolean sky) {
        if(redirect!=null)return;
        Minecraft mc=Minecraft.getInstance();
        if(mc.level==null||mc.player==null)return;
        RenderTarget main=mc.getMainRenderTarget();
        if(!(main.getColorTexture() instanceof GlTexture))return;
        if(mc.level!=lastLevel) { release();lastLevel=mc.level; }
        Vec3 eye=camera.pos;
        if(frame++%20==0)cached=find(mc.level,eye);
        if(cached.isEmpty()||failed)return;
        float partial=delta.getGameTimeDeltaPartialTick(false);
        Matrix4f savedView=new Matrix4f(mainViewMatrix);
        // Use the exact world projection captured before the GPU upload.
        Matrix4f savedProjection=new Matrix4f(camera.projectionMatrix);
        Matrix4f renderedProjection=new Matrix4f(mainProjection);
        float[] view=savedView.get(new float[16]),proj=renderedProjection.get(new float[16]);
        float[] vp=MirrorMatrices.multiply(proj,view);
        Map<String,List<MirrorSurface>> groups=new LinkedHashMap<>();
        cached.sort(Comparator.comparingDouble(s->distance(s,eye)));
        for(MirrorSurface s:cached) {
            if(distance(s,eye)>32*32||!s.plane().isFrontFacing(eye.x,eye.y,eye.z,.025))continue;
            BlockState state=mc.level.getBlockState(new BlockPos(s.x,s.y,s.z));
            if(!(state.getBlock() instanceof MirrorBlock))continue;
            if(mc.level.getBlockState(new BlockPos(s.x+s.nx,s.y+s.ny,s.z+s.nz)).getBlock() instanceof MirrorBlock)continue;
            double[] clip=MirrorMatrices.transform(vp,s.cx-eye.x,s.cy-eye.y,s.cz-eye.z,1);
            if(clip[3]<=.01||Math.abs(clip[0])>clip[3]+1.5||Math.abs(clip[1])>clip[3]+1.5)continue;
            groups.computeIfAbsent(s.planeKey(),k->new ArrayList<>()).add(s);
        }
        if(groups.isEmpty())return;
        LevelRendererAccessor access=(LevelRendererAccessor)mc.levelRenderer;
        LevelRenderState state=access.simplymirrors$state();
        GpuBufferSlice savedProj=RenderSystem.getProjectionMatrixBuffer(),savedFog=RenderSystem.getShaderFog();
        ProjectionType savedType=RenderSystem.getProjectionType();
        GpuBuffer savedGlobals=RenderSystem.getGlobalSettingsUniform();
        int passes=0;
        var visible=mc.levelRenderer.getVisibleSections();
        var savedVisible=new ArrayList<>(visible);
        try {
            if(target==null||target.width!=main.width||target.height!=main.height) {
                if(target!=null)target.destroyBuffers();target=new TextureTarget("Simply Mirrors reflection",main.width,main.height,true);
            }
            if(projectionBuffer==null)projectionBuffer=new ProjectionMatrixBuffer("Simply Mirrors projection");
            for(List<MirrorSurface> surfaces:groups.values()) {
                if(passes++>=6)break;
                MirrorSurface s=surfaces.get(0);double[] reflected=s.plane().reflectPoint(eye.x,eye.y,eye.z);
                Vec3 reflectedEye=new Vec3(reflected[0],reflected[1],reflected[2]);
                Matrix4f reflectedView=new Matrix4f().set(MirrorMatrices.multiply(view,ReflectionTransform.matrix(s.nx,s.ny,s.nz)));
                Matrix4f projection=new Matrix4f().set(MirrorMatrices.oblique(proj,reflectedView.get(new float[16]),s.nx,s.ny,s.nz,
                        s.cx-reflected[0],s.cy-reflected[1],s.cz-reflected[2],RenderSystem.getDevice().isZZeroToOne()));
                Camera virtual=new ReflectionCamera(reflectedView);virtual.setLevel(mc.level);virtual.setEntity(mc.getCameraEntity());virtual.update(delta);
                CameraAccessor ca=(CameraAccessor)(Object)virtual;ca.simplymirrors$position(reflectedEye);
                ca.simplymirrors$rotation(s.ny!=0?mc.gameRenderer.getMainCamera().yRot():s.nz!=0?180-mc.gameRenderer.getMainCamera().yRot():-mc.gameRenderer.getMainCamera().yRot(),s.ny!=0?-mc.gameRenderer.getMainCamera().xRot():mc.gameRenderer.getMainCamera().xRot());
                ca.simplymirrors$detached(true);virtual.tick();ca.simplymirrors$frustum(reflectedView,projection,reflectedEye);
                // Use loaded section geometry directly: the main camera's occlusion graph may
                // omit a sealed room on the opposite side of the mirror wall.
                visible.clear();
                var area=access.simplymirrors$viewArea();
                if(area!=null)for(var section:area.sections)
                    if(virtual.getCullFrustum().isVisible(section.getBoundingBox()))visible.add(section);
                state.reset();mc.levelRenderer.extractLevel(delta,virtual,partial);virtual.extractRenderState(state.cameraRenderState,partial);
                state.cameraRenderState.viewRotationMatrix.set(reflectedView);state.cameraRenderState.projectionMatrix.set(projection);
                state.weatherRenderState.reset();state.particlesRenderState.reset();
                RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(target.getColorTexture(),0xff9db7ca,target.getDepthTexture(),1.0);
                int oldFront=glGetInteger(GL_FRONT_FACE);
                var mv=RenderSystem.getModelViewStack();mv.pushMatrix();mv.identity();
                try(GpuBuffer globals=globals(reflectedEye,main.width,main.height,mc,partial)) {
                    redirect=target;RenderSystem.setGlobalSettingsUniform(globals);
                    RenderSystem.setProjectionMatrix(projectionBuffer.getBuffer(projection),ProjectionType.PERSPECTIVE);glFrontFace(GL_CW);
                    mc.levelRenderer.renderLevel(allocator,delta,false,state.cameraRenderState,reflectedView,fog,new Vector4f(color),sky,state.chunkSectionsToRender);
                } finally {
                    glFrontFace(oldFront);redirect=null;mv.popMatrix();
                    RenderSystem.setGlobalSettingsUniform(savedGlobals);RenderSystem.setProjectionMatrix(savedProj,savedType);RenderSystem.setShaderFog(savedFog);
                }
                int oldDraw=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
                if(compositeFbo==0)compositeFbo=glGenFramebuffers();
                glBindFramebuffer(GL_DRAW_FRAMEBUFFER,compositeFbo);
                try {
                    glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,((GlTexture)main.getColorTexture()).glId(),0);
                    glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_TEXTURE_2D,((GlTexture)main.getDepthTexture()).glId(),0);
                    if(glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("Mirror composite framebuffer incomplete");
                    glViewport(0,0,main.width,main.height);
                    for(MirrorSurface cell:surfaces)COMPOSITE.draw(((GlTexture)target.getColorTexture()).glId(),main.width,main.height,vp,eye.x,eye.y,eye.z,cell);
                    completedPasses++;
                } finally { glBindFramebuffer(GL_DRAW_FRAMEBUFFER,oldDraw); }
            }
        } catch(RuntimeException exception) {
            failed=true;java.util.logging.Logger.getLogger("simply_mirrors").log(java.util.logging.Level.SEVERE,"Mirror reflection disabled for this world after a rendering error",exception);
        } finally {
            visible.clear();visible.addAll(savedVisible);
            redirect=null;RenderSystem.setGlobalSettingsUniform(savedGlobals);RenderSystem.setProjectionMatrix(savedProj,savedType);RenderSystem.setShaderFog(savedFog);
            state.reset();mc.levelRenderer.extractLevel(delta,mc.gameRenderer.getMainCamera(),partial);
            mc.gameRenderer.getMainCamera().extractRenderState(state.cameraRenderState,partial);
            state.cameraRenderState.viewRotationMatrix.set(savedView);state.cameraRenderState.projectionMatrix.set(savedProjection);
        }
    }
    /** Terrain draw extraction queries the Camera directly instead of the renderLevel matrix. */
    private static final class ReflectionCamera extends Camera {
        private final Matrix4f reflectedView;
        ReflectionCamera(Matrix4f reflectedView) { this.reflectedView=new Matrix4f(reflectedView); }
        @Override public Matrix4f getViewRotationMatrix(Matrix4f destination) { return destination.set(reflectedView); }
    }
    private static GpuBuffer globals(Vec3 eye,int width,int height,Minecraft mc,float partial) {
        int x=Mth.floor(eye.x),y=Mth.floor(eye.y),z=Mth.floor(eye.z);
        try(MemoryStack stack=MemoryStack.stackPush()) {
            var data=Std140Builder.onStack(stack,GlobalSettingsUniform.UBO_SIZE).putIVec3(x,y,z)
                .putVec3((float)(x-eye.x),(float)(y-eye.y),(float)(z-eye.z)).putVec2(width,height).putFloat(1)
                .putFloat(((mc.level.getGameTime()%24000)+partial)/24000f).putInt(0).putInt(1).get();
            return RenderSystem.getDevice().createBuffer(()->"Simply Mirrors camera",GpuBuffer.USAGE_UNIFORM,data);
        }
    }
    private static double distance(MirrorSurface s,Vec3 eye) {double x=s.cx-eye.x,y=s.cy-eye.y,z=s.cz-eye.z;return x*x+y*y+z*z;}
    private static List<MirrorSurface> find(Level level,Vec3 eye) {
        List<MirrorSurface> out=new ArrayList<>();int cx=(int)Math.floor(eye.x/16),cz=(int)Math.floor(eye.z/16);
        for(int x=cx-2;x<=cx+2;x++)for(int z=cz-2;z<=cz+2;z++) {
            if(!level.hasChunk(x,z))continue;
            for(BlockEntity be:level.getChunk(x,z).getBlockEntities().values()) {
                if(!(be instanceof MirrorBlockEntity))continue;BlockPos pos=be.getBlockPos();BlockState state=level.getBlockState(pos);
                if(!(state.getBlock() instanceof MirrorBlock))continue;
                for(MirrorSurface face:MirrorSurface.faces(pos.getX(),pos.getY(),pos.getZ()))
                    if(!(level.getBlockState(new BlockPos(face.x+face.nx,face.y+face.ny,face.z+face.nz)).getBlock() instanceof MirrorBlock))out.add(face);
            }
        }
        out.sort(Comparator.comparingDouble(s->distance(s,eye)));return out.size()>1536?new ArrayList<>(out.subList(0,1536)):out;
    }
    public static void release() {
        if(target!=null)target.destroyBuffers();target=null;if(projectionBuffer!=null)projectionBuffer.close();projectionBuffer=null;
        if(compositeFbo!=0)glDeleteFramebuffers(compositeFbo);compositeFbo=0;COMPOSITE.close();redirect=null;cached.clear();lastLevel=null;failed=false;frame=0;
    }
}
