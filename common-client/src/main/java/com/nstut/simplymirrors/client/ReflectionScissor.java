package com.nstut.simplymirrors.client;

import com.nstut.simplymirrors.math.MirrorCoverage;
import com.nstut.simplymirrors.math.MirrorSurface;
import com.nstut.simplymirrors.math.ReflectionOutputGuard;
import com.nstut.simplymirrors.math.ShaderViewportCrop;
import com.nstut.simplymirrors.math.ShaderTailCrop;
import com.nstut.simplymirrors.math.ShaderFinalOriginCrop;
import com.nstut.simplymirrors.math.ShaderEarlyCompositeCrop;
import com.nstut.simplymirrors.math.ShaderFinalDualCrop;
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
    // r5.9.3 HIGH GPU experiment only: never enabled in production by default.
    private static final boolean SHADER_TAIL_CROP =
        Boolean.getBoolean("simplymirrors.shaderTailCrop");
    // Separate opt-in experiment: the pinned shader's final pass performs an
    // origin-fragment SSBO clear. Keep that fragment INSIDE the scissor.
    private static final boolean SHADER_FINAL_ORIGIN_CROP =
        Boolean.getBoolean("simplymirrors.shaderFinalOriginCrop");
    // Separate opt-in experiment for the expensive composite SSR producer.
    private static final boolean SHADER_EARLY_COMPOSITE_CROP =
        Boolean.getBoolean("simplymirrors.shaderEarlyCompositeCrop");
    // The pinned final pass updates a global SSBO at pixel (0,0). Draw that
    // one pixel separately, never crop global state updates.
    private static final boolean SHADER_FINAL_DUAL_CROP =
        Boolean.getBoolean("simplymirrors.shaderFinalDualCrop");
    // These shader IDs are GL-context scoped and removed on program destruction.
    private static final java.util.Map<Integer,String> PROGRAM_NAMES =
        new java.util.HashMap<Integer,String>();
    public static void registerShaderProgram(int program, String name) {
        if ((SHADER_TAIL_CROP || SHADER_FINAL_ORIGIN_CROP || SHADER_EARLY_COMPOSITE_CROP || SHADER_FINAL_DUAL_CROP) && program > 0 && name != null)
            PROGRAM_NAMES.put(program, name);
    }
    public static String profileProgramName(int program) {
        return PROGRAM_NAMES.get(program);
    }
    public static void unregisterShaderProgram(int program) {
        PROGRAM_NAMES.remove(program);
    }
    private static final int SHADER_CROP_PAD =
        Math.max(256,Math.min(1024,Integer.getInteger("simplymirrors.shaderCropPad",512)));
    private static boolean inFinalStage;
    private static long shaderQuadChecks, shaderQuadsClipped;
    private static long finalOriginQuadsClipped, finalOriginPixelsSaved;
    private static long earlyCompositeQuadsClipped, earlyCompositePixelsSaved;
    private static long finalDualQuadsClipped, finalDualPixelsSaved, finalDualOriginDraws;
    private static int finalDualDiagnostics;
    private static void finalDualDenied(String reason) {
        if (Boolean.getBoolean("simplymirrors.gpuProfile") &&
                finalDualDiagnostics++ < 4)
            System.out.println("SIMPLY_MIRRORS_FINAL_DUAL_DENIED "+reason);
    }
    private static boolean finalDualPending;
    private static int finalDualPendingProgram, finalDualPendingFbo;
    // Diagnostic only, limited to four messages per client and GPU profiler flag.
    private static int earlyCompositeDiagnostics;
    private static void earlyCompositeDenied(String reason) {
        if (Boolean.getBoolean("simplymirrors.gpuProfile") &&
                earlyCompositeDiagnostics++ < 4)
            System.out.println("SIMPLY_MIRRORS_EARLY_COMPOSITE_DENIED " + reason);
    }
    // Expensive GL framebuffer queries have no value when coverage nearly fills
    // the viewport; reject those mirror groups once per reflection pass.
    public static boolean postProcessCropPossible() {
        ReflectionScissor scope=current;
        if (scope==null || !scope.active || !scope.cropWorthwhile) return false;
        // Oculus runs the SSR-producing "composite" program BEFORE the
        // finalizeLevelRendering callback. The previous candidate incorrectly
        // gated it on inFinalStage and could never clip a single fragment.
        if (SHADER_EARLY_COMPOSITE_CROP &&
                ShaderEarlyCompositeCrop.isEligible(
                    PROGRAM_NAMES.get(glGetInteger(GL_CURRENT_PROGRAM))))
            return true;
        return inFinalStage && (SHADER_POSTPROCESS_CROP || SHADER_TAIL_CROP ||
                SHADER_FINAL_ORIGIN_CROP || SHADER_FINAL_DUAL_CROP);
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
        cropWorthwhile=(SHADER_POSTPROCESS_CROP &&
            ShaderViewportCrop.rectangle(x,y,width,height,frameWidth,frameHeight,
                0,0,frameWidth,frameHeight,SHADER_CROP_PAD)!=null) ||
            (SHADER_TAIL_CROP &&
            ShaderViewportCrop.rectangle(x,y,width,height,frameWidth,frameHeight,
                0,0,frameWidth,frameHeight,48)!=null) ||
            (SHADER_FINAL_ORIGIN_CROP &&
            ShaderFinalOriginCrop.rectangle(x,y,width,height,frameWidth,frameHeight,
                0,0,frameWidth,frameHeight,96)!=null) ||
            (SHADER_EARLY_COMPOSITE_CROP &&
            ShaderEarlyCompositeCrop.rectangle("composite",x,y,width,height,
                frameWidth,frameHeight,0,0,frameWidth,frameHeight)!=null) ||
            (SHADER_FINAL_DUAL_CROP &&
            ShaderFinalDualCrop.rectangle("final",x,y,width,height,
                frameWidth,frameHeight,0,0,frameWidth,frameHeight)!=null);
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
        int padding=SHADER_CROP_PAD;
        String program=null;
        boolean finalOrigin=false;
        boolean finalDual=false;
        boolean earlyComposite=false;
        if(SHADER_TAIL_CROP || SHADER_FINAL_ORIGIN_CROP ||
                SHADER_EARLY_COMPOSITE_CROP || SHADER_FINAL_DUAL_CROP) {
            // Only exact recognized programs may reduce work; all other
            // shader programs retain full-frame native rendering.
            program=PROGRAM_NAMES.get(glGetInteger(GL_CURRENT_PROGRAM));
            finalOrigin=SHADER_FINAL_ORIGIN_CROP && ShaderFinalOriginCrop.isPinnedFinal(program);
            finalDual=SHADER_FINAL_DUAL_CROP && ShaderFinalOriginCrop.isPinnedFinal(program);
            earlyComposite=SHADER_EARLY_COMPOSITE_CROP &&
                    ShaderEarlyCompositeCrop.isEligible(program);
            if(!finalOrigin && !finalDual && !earlyComposite) {
                if(!SHADER_TAIL_CROP)return false;
                padding=ShaderTailCrop.paddingForProgram(program);
                if(padding<0)return false;
            }
        }
        shaderQuadChecks++;
        int fbo=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        if(fbo==0) {
            if(earlyComposite)earlyCompositeDenied("fbo=0");
            if(finalDual)finalDualDenied("fbo=0");
            return false;
        }
        int drawBuffer=glGetInteger(GL_DRAW_BUFFER);
        int attachment=glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER,
                GL_COLOR_ATTACHMENT0,GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        if(drawBuffer!=GL_COLOR_ATTACHMENT0 || attachment!=GL_TEXTURE) {
            if(earlyComposite)earlyCompositeDenied("fbo="+fbo+
                    " drawBuffer="+drawBuffer+" color0Type="+attachment);
            if(finalDual)finalDualDenied("fbo="+fbo+
                    " drawBuffer="+drawBuffer+" color0Type="+attachment);
            return false;
        }
        IntBuffer viewport=BufferUtils.createIntBuffer(4);
        glGetIntegerv(GL_VIEWPORT,viewport);
        // Verify final writes to the reflection output and not an unrelated
        // intermediate target. That FBO may be an Iris alias of the mirror's
        // main color texture, so compare attachment identity, not FBO names.
        if (finalDual && glGetFramebufferAttachmentParameteri(
                GL_DRAW_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,
                GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME) !=
                MirrorRenderer.redirect.getColorTextureId()) {
            finalDualDenied("final output not attached to mirror texture");
            return false;
        }
        int[] box=finalDual
            ? ShaderFinalDualCrop.rectangle(program,scope.x,scope.y,
                  scope.width,scope.height,scope.frameWidth,scope.frameHeight,
                  viewport.get(0),viewport.get(1),viewport.get(2),viewport.get(3))
            : finalOrigin
            ? ShaderFinalOriginCrop.rectangle(scope.x,scope.y,scope.width,scope.height,
                  scope.frameWidth,scope.frameHeight,viewport.get(0),viewport.get(1),
                  viewport.get(2),viewport.get(3),96)
            : earlyComposite
            ? ShaderEarlyCompositeCrop.rectangle(program,scope.x,scope.y,scope.width,
                  scope.height,scope.frameWidth,scope.frameHeight,
                  viewport.get(0),viewport.get(1),viewport.get(2),viewport.get(3))
            : ShaderViewportCrop.rectangle(scope.x,scope.y,scope.width,scope.height,
                  scope.frameWidth,scope.frameHeight,viewport.get(0),viewport.get(1),
                  viewport.get(2),viewport.get(3),padding);
        if(box==null) {
            if(finalDual)finalDualDenied("unsupported or too-large final crop");
            if(earlyComposite)earlyCompositeDenied(
                    "geometry viewport="+viewport.get(0)+","+viewport.get(1)+","+
                    viewport.get(2)+","+viewport.get(3)+
                    " native="+scope.frameWidth+"x"+scope.frameHeight+
                    " mirror="+scope.x+","+scope.y+","+scope.width+","+scope.height);
            return false;
        }
        glScissor(box[0],box[1],box[2],box[3]);
        glEnable(GL_SCISSOR_TEST);
        shaderQuadsClipped++;
        if(finalDual) {
            if (finalDualPending) throw new IllegalStateException(
                    "Final shader origin draw was not consumed by the previous quad");
            finalDualPendingProgram=glGetInteger(GL_CURRENT_PROGRAM);
            finalDualPendingFbo=fbo;
            finalDualPending=true;
            finalDualQuadsClipped++;
            finalDualPixelsSaved += (long)viewport.get(2)*viewport.get(3)
                    - (long)box[2]*box[3] - 1;
        }
        if(finalOrigin) {
            finalOriginQuadsClipped++;
            finalOriginPixelsSaved += (long)viewport.get(2)*viewport.get(3)
                    - (long)box[2]*box[3];
        }
        if(earlyComposite) {
            earlyCompositeQuadsClipped++;
            earlyCompositePixelsSaved += (long)viewport.get(2)*viewport.get(3)
                    - (long)box[2]*box[3];
        }
        return true;
    }

    /**
     * Called from the pinned Iris/Oculus 1.20.1 fullscreen quad after its
     * first cropped draw but before polygon-mode restoration. The same shader,
     * VAO, framebuffer and fullscreen quad draw exactly one extra fragment.
     */
    public static void drawFinalOriginPixel(Runnable draw) {
        if (!finalDualPending) return;
        if (glGetInteger(GL_CURRENT_PROGRAM)!=finalDualPendingProgram ||
                glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING)!=finalDualPendingFbo ||
                !glIsEnabled(GL_SCISSOR_TEST))
            throw new IllegalStateException("Final shader origin replay lost GL state");
        IntBuffer bounds=BufferUtils.createIntBuffer(4);
        glGetIntegerv(GL_SCISSOR_BOX,bounds);
        try {
            glScissor(0,0,1,1);
            draw.run();
            finalDualOriginDraws++;
        } finally {
            glScissor(bounds.get(0),bounds.get(1),bounds.get(2),bounds.get(3));
            finalDualPending=false;
            finalDualPendingProgram=finalDualPendingFbo=0;
        }
    }

    /** Fail loudly in the test client if the mandatory 1x1 origin draw did not execute. */
    public static void requireFinalOriginPixelDraw() {
        if (finalDualPending) {
            finalDualPending=false;
            finalDualPendingProgram=finalDualPendingFbo=0;
            throw new IllegalStateException("Final shader scissor skipped the mandatory origin fragment");
        }
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
        if((SHADER_POSTPROCESS_CROP || SHADER_TAIL_CROP ||
                SHADER_FINAL_ORIGIN_CROP || SHADER_EARLY_COMPOSITE_CROP ||
                SHADER_FINAL_DUAL_CROP)
                && Boolean.getBoolean("simplymirrors.gpuProfile") &&
           clipped>0 && clipped%180==0)
            System.out.println("SIMPLY_MIRRORS_SHADER_CROP quadsChecked="+
                shaderQuadChecks+" quadsClipped="+shaderQuadsClipped+
                " finalOriginQuadsClipped="+finalOriginQuadsClipped+
                " finalOriginPixelsSaved="+finalOriginPixelsSaved+
                " earlyCompositeQuadsClipped="+earlyCompositeQuadsClipped+
                " earlyCompositePixelsSaved="+earlyCompositePixelsSaved+
                " finalDualQuadsClipped="+finalDualQuadsClipped+
                " finalDualOriginDraws="+finalDualOriginDraws+
                " finalDualPixelsSaved="+finalDualPixelsSaved);
        glScissor(oldX,oldY,oldWidth,oldHeight);
        if(oldEnabled)glEnable(GL_SCISSOR_TEST);
        else glDisable(GL_SCISSOR_TEST);
        // Keep failure handling after GL state cleanup. In normal operation
        // the quad return hook consumed and verified this mandatory fragment.
        requireFinalOriginPixelDraw();
    }
}
