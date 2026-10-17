package com.nstut.simplymirrors.client;

import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.nstut.simplymirrors.block.*;
import com.nstut.simplymirrors.math.*;
import com.nstut.simplymirrors.client.mixin.CameraAccessor;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import java.nio.FloatBuffer;
import java.util.*;
import static org.lwjgl.opengl.GL33.*;

/** CE reflection transform adapted to a bounded, same-dimension vanilla world render. */
public final class MirrorRenderer {
    public static RenderTarget redirect;
    public static long completedPasses;
    private static TextureTarget target;
    private static final GlMirrorComposite COMPOSITE=new GlMirrorComposite();
    private static Level lastLevel;
    private static List<MirrorSurface> cached=new ArrayList<>();
    private static int frame;
    private static boolean failed;
    private MirrorRenderer() { }

    public static void render(DeltaTracker delta,Camera camera,GameRenderer game,LightTexture light,Matrix4f mainViewMatrix,Matrix4f mainProjectionMatrix) {
        if(redirect!=null)return;
        Minecraft mc=Minecraft.getInstance();
        if(mc.level==null||mc.player==null)return;
        float partial=delta.getGameTimeDeltaPartialTick(false);
        Vec3 eye=camera.getPosition();
        if(mc.level!=lastLevel) { release();lastLevel=mc.level; }
        if(frame++%20==0)cached=find(mc.level,eye);
        if(cached.isEmpty()||failed)return;
        float[] view=array(mainViewMatrix),proj=array(mainProjectionMatrix),vp=MirrorMatrices.multiply(proj,view);
        Map<String,List<MirrorSurface>> groups=new LinkedHashMap<>();
        cached.sort(Comparator.comparingDouble(s->distance(s,eye)));
        for(MirrorSurface s:cached) {
            if(distance(s,eye)>32*32||!s.plane().isFrontFacing(eye.x,eye.y,eye.z,.025))continue;
            BlockState state=mc.level.getBlockState(new BlockPos(s.x,s.y,s.z));
            if(!(state.getBlock() instanceof MirrorBlock))continue;
            Direction facing=state.getValue(MirrorBlock.FACING);
            if(facing.getStepX()!=s.nx||facing.getStepZ()!=s.nz||!visible(s,vp,eye))continue;
            groups.computeIfAbsent(s.planeKey(),k->new ArrayList<>()).add(s);
        }
        RenderTarget main=mc.getMainRenderTarget();int passes=0;
        try {
            if(target==null||target.width!=main.width||target.height!=main.height) {
                if(target!=null)target.destroyBuffers();target=new TextureTarget(main.width,main.height,true,Minecraft.ON_OSX);
            }
            for(List<MirrorSurface> surfaces:groups.values()) {
                if(passes++>=2)break;
                MirrorSurface surface=surfaces.get(0);
                double[] reflected=surface.plane().reflectPoint(eye.x,eye.y,eye.z);
                Vec3 reflectedEye=new Vec3(reflected[0],reflected[1],reflected[2]);
                Matrix4f reflectedView=matrix(MirrorMatrices.multiply(view,ImmersiveMirrorTransform.matrix(surface.nx,0,surface.nz)));
                Matrix4f projection=matrix(MirrorMatrices.oblique(proj,array(reflectedView),surface.nx,0,surface.nz,
                    surface.cx-reflected[0],surface.cy-reflected[1],surface.cz-reflected[2],false));
                Camera virtual=new Camera();virtual.setup(mc.level,mc.getCameraEntity(),true,false,partial);
                CameraAccessor accessor=(CameraAccessor)(Object)virtual;accessor.simplymirrors$position(reflectedEye);
                accessor.simplymirrors$rotation(surface.nz!=0?180-camera.getYRot():-camera.getYRot(),camera.getXRot());
                PoseStack pose=new PoseStack();pose.last().pose().set(reflectedView);pose.last().normal().set(new org.joml.Matrix3f(reflectedView));
                int oldFront=glGetInteger(GL_FRONT_FACE);
                redirect=target;
                try {
                    target.bindWrite(true);RenderSystem.setProjectionMatrix(projection,com.mojang.blaze3d.vertex.VertexSorting.DISTANCE_TO_ORIGIN);glFrontFace(GL_CW);
                    mc.levelRenderer.prepareCullFrustum(reflectedEye,reflectedView,projection);
                    mc.levelRenderer.renderLevel(delta,false,virtual,game,light,reflectedView,projection);
                } finally {
                    glFrontFace(oldFront);redirect=null;main.bindWrite(true);RenderSystem.setProjectionMatrix(mainProjectionMatrix,com.mojang.blaze3d.vertex.VertexSorting.DISTANCE_TO_ORIGIN);
                    mc.levelRenderer.prepareCullFrustum(eye,mainViewMatrix,mainProjectionMatrix);
                    mc.getEntityRenderDispatcher().prepare(mc.level,camera,mc.crosshairPickEntity);
                }
                for(MirrorSurface cell:surfaces)COMPOSITE.draw(target.getColorTextureId(),main.width,main.height,vp,eye.x,eye.y,eye.z,cell);
                completedPasses++;
            }
        } catch(RuntimeException exception) {
            failed=true;java.util.logging.Logger.getLogger("simply_mirrors").log(java.util.logging.Level.SEVERE,"Mirror reflection disabled for this world after a rendering error",exception);
        } finally { redirect=null;main.bindWrite(true);RenderSystem.setProjectionMatrix(mainProjectionMatrix,com.mojang.blaze3d.vertex.VertexSorting.DISTANCE_TO_ORIGIN); }
    }
    private static boolean visible(MirrorSurface s,float[] vp,Vec3 eye) {
        double[] clip=MirrorMatrices.transform(vp,s.cx-eye.x,s.cy-eye.y,s.cz-eye.z,1);
        return clip[3]>.01&&Math.abs(clip[0])<clip[3]+1.5&&Math.abs(clip[1])<clip[3]+1.5;
    }
    private static double distance(MirrorSurface s,Vec3 eye) { double x=s.cx-eye.x,y=s.cy-eye.y,z=s.cz-eye.z;return x*x+y*y+z*z; }
    private static List<MirrorSurface> find(Level level,Vec3 eye) {
        List<MirrorSurface> surfaces=new ArrayList<>();int cx=(int)Math.floor(eye.x/16),cz=(int)Math.floor(eye.z/16);
        for(int x=cx-2;x<=cx+2;x++)for(int z=cz-2;z<=cz+2;z++) {
            if(!level.hasChunk(x,z))continue;
            for(BlockEntity be:level.getChunk(x,z).getBlockEntities().values()) {
                if(!(be instanceof MirrorBlockEntity))continue;BlockPos pos=be.getBlockPos();BlockState state=level.getBlockState(pos);
                if(!(state.getBlock() instanceof MirrorBlock))continue;Direction f=state.getValue(MirrorBlock.FACING);
                surfaces.add(new MirrorSurface(pos.getX(),pos.getY(),pos.getZ(),f.getStepX(),f.getStepZ()));
            }
        }
        surfaces.sort(Comparator.comparingDouble(s->distance(s,eye)));
        return surfaces.size()>256?new ArrayList<>(surfaces.subList(0,256)):surfaces;
    }
    public static void release() {
        if(target!=null)target.destroyBuffers();target=null;redirect=null;cached.clear();lastLevel=null;frame=0;failed=false;COMPOSITE.close();
    }
    private static float[] array(Matrix4f matrix) { return matrix.get(new float[16]); }
    private static Matrix4f matrix(float[] values) { return new Matrix4f().set(values); }
}
