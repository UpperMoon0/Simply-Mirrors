package com.nstut.simplymirrors.client;

import com.nstut.simplymirrors.math.MirrorCoverage;
import com.nstut.simplymirrors.math.MirrorSurface;
import com.nstut.simplymirrors.math.ReflectionOutputGuard;
import com.nstut.simplymirrors.math.ShaderViewportCrop;
import org.lwjgl.BufferUtils;
import java.nio.IntBuffer;
import java.util.List;
import static org.lwjgl.opengl.GL33.*;

/**
 * Full-resolution, pixel-exact planar reflection coverage culling.
 *
 * Drawing a 150-pixel mirror normally executes expensive Oculus/Complementary
 * fragments at every pixel of the whole screen. The reflected camera's raster
 * positions have exactly the same viewport coordinates as the original camera
 * over the planar face. Limit shader draws to the conservative union of those
 * faces, with broad padding for TAA, bloom, interpolation and postprocessing.
 *
 * Geometry, shadow-map rasterization, and final mirror compositing retain
 * their original resolution. No texture downscaling or frame skipping.
 */
public final class ReflectionScissor implements AutoCloseable {
    private static final boolean ENABLED =
        !"false".equalsIgnoreCase(System.getProperty("simplymirrors.coverageScissor", "true"));
    private static final ReflectionScissor NOOP = new ReflectionScissor();
    // Disabled until native shader/pixel A/B shows exact correctness on every target.
    private static final boolean OUTPUT_SCISSOR =
        Boolean.getBoolean("simplymirrors.reflectionOutputScissor");
    private static ReflectionScissor current;
    private static final boolean SHADER_POSTPROCESS_CROP =
        Boolean.getBoolean("simplymirrors.shaderPostProcessCrop");
    private static final int SHADER_CROP_PAD =
        Math.max(256,Math.min(1024,Integer.getInteger("simplymirrors.shaderCropPad",512)));
    private static boolean inFinalStage;
    private static long shaderQuadChecks, shaderQuadsClipped;
    // Expensive GL framebuffer queries have no value when coverage nearly fills
    // the viewport; reject those mirror groups once per reflection pass.
    public static boolean postProcessCropPossible() {
        ReflectionScissor scope=current;
        return SHADER_POSTPROCESS_CROP && inFinalStage && scope!=null &&
               scope.active && scope.cropWorthwhile;
    }
    public static void setFinalStage(boolean finalStage) { inFinalStage=finalStage; }
    private static long outputQuadChecks, outputQuadClipped, outputAliasFboClipped, outputTextureMatches;
    public static boolean outputScissorEnabled() { return OUTPUT_SCISSOR; }
    private final boolean active;
    private final boolean oldEnabled;
    private final int oldX, oldY, oldWidth, oldHeight;
    private final int mirrorFrameBuffer, mirrorColorTexture, frameWidth, frameHeight;
    private final int x, y, width, height;
    private final boolean cropWorthwhile;
    private static int clipped, skipped;

    private ReflectionScissor() {
        active=false;oldEnabled=false;oldX=oldY=oldWidth=oldHeight=0;
        mirrorFrameBuffer=mirrorColorTexture=frameWidth=frameHeight=x=y=width=height=0;
        cropWorthwhile=false;
    }

    private ReflectionScissor(int x, int y, int width, int height, int frameWidth, int frameHeight) {
        active=true;
        this.x=x;this.y=y;this.width=width;this.height=height;
        this.frameWidth=frameWidth;this.frameHeight=frameHeight;
        cropWorthwhile=SHADER_POSTPROCESS_CROP &&
            ShaderViewportCrop.rectangle(x,y,width,height,frameWidth,frameHeight,
                0,0,frameWidth,frameHeight,SHADER_CROP_PAD)!=null;
        mirrorFrameBuffer=OUTPUT_SCISSOR?glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING):0;
        mirrorColorTexture=OUTPUT_SCISSOR && mirrorFrameBuffer>0 &&
            glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE)==GL_TEXTURE
            ?glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME):0;
        oldEnabled=glIsEnabled(GL_SCISSOR_TEST);
        IntBuffer previous=BufferUtils.createIntBuffer(4);
        glGetIntegerv(GL_SCISSOR_BOX, previous);
        oldX=previous.get(0);oldY=previous.get(1);
        oldWidth=previous.get(2);oldHeight=previous.get(3);
        glScissor(x,y,width,height);
        glEnable(GL_SCISSOR_TEST);
        current=this;
    }

    public static ReflectionScissor begin(float[] viewProjection,
                                          double eyeX,double eyeY,double eyeZ,
                                          int width,int height,List<MirrorSurface> faces) {
        if(!ENABLED || faces.isEmpty() || width<=0 || height<=0) return NOOP;
        int[] bounds=calculate(viewProjection,eyeX,eyeY,eyeZ,width,height,faces);
        if(bounds==null) {
            skipped++;
            return NOOP;
        }
        clipped++;
        if(Boolean.getBoolean("simplymirrors.gpuProfile") && (clipped+skipped)%180==0)
            System.out.println("SIMPLY_MIRRORS_COVERAGE_SCISSOR clipped="+clipped+
                " fallback="+skipped+" rect="+bounds[0]+","+bounds[1]+","+bounds[2]+","+bounds[3]+
                " full="+width+"x"+height);
        return new ReflectionScissor(bounds[0],bounds[1],bounds[2],bounds[3],width,height);
    }

    /** Pure projection bounds are unit-tested in the common math module. */
    static int[] calculate(float[] vp,double eyeX,double eyeY,double eyeZ,
                           int width,int height,List<MirrorSurface> faces) {
        return MirrorCoverage.scissor(vp,eyeX,eyeY,eyeZ,width,height,faces);
    }

    /**
     * Restrict only a fullscreen draw going directly into this reflection's
     * native-resolution output FBO. Every deferred/half-res/history target is
     * left untouched because it can feed another shader pass outside coverage.
     *
     * The caller must restore its original GL_SCISSOR_BOX and enable state.
     */
    public static boolean constrainFinalOutputQuad() {
        ReflectionScissor scope=current;
        if(!OUTPUT_SCISSOR || scope==null || !scope.active || scope.mirrorColorTexture==0)
            return false;
        outputQuadChecks++;
        int bound=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        if(bound==0 || glGetInteger(GL_DRAW_BUFFER)!=GL_COLOR_ATTACHMENT0)
            return false;
        // Iris's FinalPassRenderer uses a *different FBO name* whose color0
        // attachment aliases Minecraft's main color texture. Match actual
        // GL storage identity rather than incorrectly requiring FBO equality.
        if(glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,
              GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE)!=GL_TEXTURE)
            return false;
        int colorTexture=glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER,
              GL_COLOR_ATTACHMENT0,GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        if(colorTexture!=scope.mirrorColorTexture) return false;
        outputTextureMatches++;
        IntBuffer viewport=BufferUtils.createIntBuffer(4);
        glGetIntegerv(GL_VIEWPORT,viewport);
        if(!ReflectionOutputGuard.matches(scope.mirrorColorTexture,colorTexture,
              scope.frameWidth,scope.frameHeight,
              viewport.get(0),viewport.get(1),viewport.get(2),viewport.get(3)))
            return false;
        glScissor(scope.x,scope.y,scope.width,scope.height);
        glEnable(GL_SCISSOR_TEST);
        outputQuadClipped++;
        if(bound!=scope.mirrorFrameBuffer)outputAliasFboClipped++;
        return true;
    }

    /**
     * Opt-in shader intermediate-pass experiment. Uniforms, target images,
     * framebuffer dimensions and shader resolution remain native; only raster
     * fragments outside a generously padded mirror region are skipped.
     * This is NOT safe to enable for arbitrary shaders without image A/B.
     */
    public static boolean constrainPostProcessQuad() {
        ReflectionScissor scope=current;
        if(!postProcessCropPossible())return false;
        shaderQuadChecks++;
        int fbo=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        if(fbo==0 || glGetInteger(GL_DRAW_BUFFER)!=GL_COLOR_ATTACHMENT0 ||
           glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,
              GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE)!=GL_TEXTURE)
            return false;
        IntBuffer viewport=BufferUtils.createIntBuffer(4);
        glGetIntegerv(GL_VIEWPORT,viewport);
        int[] box=ShaderViewportCrop.rectangle(scope.x,scope.y,scope.width,scope.height,
              scope.frameWidth,scope.frameHeight,viewport.get(0),viewport.get(1),
              viewport.get(2),viewport.get(3),SHADER_CROP_PAD);
        if(box==null)return false;
        glScissor(box[0],box[1],box[2],box[3]);
        glEnable(GL_SCISSOR_TEST);
        shaderQuadsClipped++;
        return true;
    }

    @Override public void close() {
        if(!active)return;
        if(current==this) current=null;
        inFinalStage=false;
        if(OUTPUT_SCISSOR && Boolean.getBoolean("simplymirrors.gpuProfile") &&
           clipped>0 && clipped%180==0)
            System.out.println("SIMPLY_MIRRORS_OUTPUT_SCISSOR quadsChecked="+
                outputQuadChecks+" textureMatches="+outputTextureMatches+
                " outputQuadsClipped="+outputQuadClipped+" aliasedFboClips="+outputAliasFboClipped);
        if(SHADER_POSTPROCESS_CROP && Boolean.getBoolean("simplymirrors.gpuProfile") &&
           clipped>0 && clipped%180==0)
            System.out.println("SIMPLY_MIRRORS_SHADER_CROP quadsChecked="+
                shaderQuadChecks+" quadsClipped="+shaderQuadsClipped);
        glScissor(oldX,oldY,oldWidth,oldHeight);
        if(oldEnabled)glEnable(GL_SCISSOR_TEST);
        else glDisable(GL_SCISSOR_TEST);
    }
}
