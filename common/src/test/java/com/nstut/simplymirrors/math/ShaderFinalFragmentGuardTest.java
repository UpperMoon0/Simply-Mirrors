package com.nstut.simplymirrors.math;

import java.util.EnumMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class ShaderFinalFragmentGuardTest {
    private enum Stage { VERTEX, FRAGMENT }
    private static final String PINNED = "#version 330 core\n"
            + "void clearSSBOs(){ if (gl_FragCoord.x + gl_FragCoord.y < 1.5) { } }\n"
            + "void main() { clearSSBOs(); gl_FragData[0] = vec4(1.0); }\n";

    @Test public void preservesOriginPixelAndSkipsOnlyOutOfRoiFragments() {
        String patched = ShaderFinalFragmentGuard.transformFragment(PINNED);
        assertTrue(patched.contains("gl_FragCoord.x + gl_FragCoord.y >= 1.5) discard;"));
        assertTrue(patched.contains("simplymirrors_FinalFragmentRect.z > simplymirrors_FinalFragmentRect.x"));
        assertTrue(patched.indexOf("discard;") < patched.indexOf("clearSSBOs(); gl_FragData"));
        assertEquals(patched, ShaderFinalFragmentGuard.transformFragment(patched));
    }

    @Test public void onlyCopiesRecognizedFinalFragmentAndLeavesOtherStagesUntouched() {
        Map<Stage,String> original = new EnumMap<>(Stage.class);
        original.put(Stage.VERTEX, "#version 330 core\nvoid main() {}");
        original.put(Stage.FRAGMENT, PINNED);
        assertSame(original, ShaderFinalFragmentGuard.transformProgram("composite", original));
        Map<Stage,String> patched = ShaderFinalFragmentGuard.transformProgram("final", original);
        assertNotSame(original, patched);
        assertEquals(original.get(Stage.VERTEX), patched.get(Stage.VERTEX));
        assertEquals(PINNED, original.get(Stage.FRAGMENT));
        assertTrue(patched.get(Stage.FRAGMENT).contains("simplymirrors_FinalFragmentRect"));
    }

    @Test public void unknownSourceOrCommentedOriginConditionFailsClosed() {
        String unrelated = "#version 330 core\nvoid main() {gl_FragData[0]=vec4(1);}";
        assertSame(unrelated, ShaderFinalFragmentGuard.transformFragment(unrelated));
        String fake = "#version 330 core\n// gl_FragCoord.x + gl_FragCoord.y < 1.5\n"
                + "void clearSSBOs(){}\nvoid main(){clearSSBOs(); gl_FragData[0]=vec4(1);}";
        assertSame(fake, ShaderFinalFragmentGuard.transformFragment(fake));
    }

    @Test public void toleratesAstPrinterWhitespaceWithoutRelaxingOriginSafety() {
        String formatted = "#version 330 core\n"
                + "void clearSSBOs( ){if(gl_FragCoord . x+gl_FragCoord.y<1.5){}}\n"
                + "void main(){clearSSBOs ( ) ; gl_FragData [ 0 ]=vec4(1.0);}";
        assertTrue(ShaderFinalFragmentGuard.transformFragment(formatted)
                .contains("simplymirrors_FinalFragmentRect"));
    }

    @Test public void unrelatedOriginCheckCannotAuthorizeFragmentDiscard() {
        String unsafe = "#version 330 core\n"
                + "void clearSSBOs(){ int x=1; }\n"
                + "void unrelated(){ if(gl_FragCoord.x + gl_FragCoord.y < 1.5){} }\n"
                + "void main(){clearSSBOs(); gl_FragData[0]=vec4(1.0);}";
        assertSame(unsafe, ShaderFinalFragmentGuard.transformFragment(unsafe));
    }

    @Test public void ambiguousMultipleMainFunctionsFailClosed() {
        String unsafe = PINNED + "void main() { clearSSBOs(); }\n";
        assertSame(unsafe, ShaderFinalFragmentGuard.transformFragment(unsafe));
    }

    @Test public void resetMustPrecedeColorOutput() {
        String late = PINNED.replace("clearSSBOs(); gl_FragData[0]", "gl_FragData[0]")
                .replace("vec4(1.0); }", "vec4(1.0); clearSSBOs(); }");
        assertSame(late, ShaderFinalFragmentGuard.transformFragment(late));
    }

    @Test public void resetInLaterHelperDoesNotAuthorizeDiscard() {
        String unsafe = "#version 330 core\n"
                + "void clearSSBOs(){if(gl_FragCoord.x + gl_FragCoord.y < 1.5){}}\n"
                + "void main(){gl_FragData[0]=vec4(1.0);}\n"
                + "void helper(){clearSSBOs();}\n";
        assertSame(unsafe, ShaderFinalFragmentGuard.transformFragment(unsafe));
    }
    @Test public void nestedClearAndMainBlocksAreParsedAsWholeFunctions() {
        String safe = "#version 330 core\n"
                + "void clearSSBOs(){ if (gl_FragCoord.x + gl_FragCoord.y < 1.5) { int a=1; } }\n"
                + "void main(){ if(true){ int x=1; } clearSSBOs(); gl_FragData[0]=vec4(1.0); }";
        assertTrue(ShaderFinalFragmentGuard.transformFragment(safe)
                .contains("simplymirrors_FinalFragmentRect"));
        String unsafe = "#version 330 core\n"
                + "void clearSSBOs(){ if(true){} if(gl_FragCoord.x + gl_FragCoord.y < 1.5){} }\n"
                + "void main(){ if(true){ gl_FragData[0]=vec4(1.0); } }\n"
                + "void helper(){clearSSBOs();}";
        assertSame(unsafe, ShaderFinalFragmentGuard.transformFragment(unsafe));
    }
    @Test public void noMutationWhenOnlyVertexStageExists() {
        Map<Stage,String> stages = new EnumMap<>(Stage.class);
        stages.put(Stage.VERTEX, PINNED);
        assertSame(stages, ShaderFinalFragmentGuard.transformProgram("final", stages));
    }
    @Test public void colorOutputInLaterHelperDoesNotAuthorizeDiscard() {
        String unsafe = "#version 330 core\n"
                + "void clearSSBOs(){if(gl_FragCoord.x + gl_FragCoord.y < 1.5){}}\n"
                + "void main(){clearSSBOs();}\n"
                + "void helper(){gl_FragData[0]=vec4(1.0);}\n";
        assertSame(unsafe, ShaderFinalFragmentGuard.transformFragment(unsafe));
    }
    @Test public void conditionalResetCannotAuthorizeFragmentDiscard() {
        String unsafe = "#version 330 core\n"
                + "void clearSSBOs(){if(gl_FragCoord.x + gl_FragCoord.y < 1.5){}}\n"
                + "void main(){if(true){clearSSBOs();} gl_FragData[0]=vec4(1.0);}";
        assertSame(unsafe, ShaderFinalFragmentGuard.transformFragment(unsafe));
    }    @Test public void earlyExitBeforeResetCannotAuthorizeFragmentDiscard() {
        String unsafe = "#version 330 core\n"
                + "void clearSSBOs(){if(gl_FragCoord.x + gl_FragCoord.y < 1.5){}}\n"
                + "void main(){if(true){return;} clearSSBOs(); gl_FragData[0]=vec4(1.0);}";
        assertSame(unsafe, ShaderFinalFragmentGuard.transformFragment(unsafe));
    }    @Test public void bracelessConditionalResetFailsClosed() {
        String unsafe = "#version 330 core\n"
                + "void clearSSBOs(){if(gl_FragCoord.x + gl_FragCoord.y < 1.5){}}\n"
                + "void main(){if(false) clearSSBOs(); gl_FragData[0]=vec4(1.0);}";
        assertSame(unsafe, ShaderFinalFragmentGuard.transformFragment(unsafe));
    }    @Test public void multipleResetCallsCannotAuthorizeFragmentDiscard() {
        String unsafe = PINNED.replace("clearSSBOs(); gl_FragData", "clearSSBOs(); clearSSBOs(); gl_FragData");
        assertSame(unsafe, ShaderFinalFragmentGuard.transformFragment(unsafe));
    }
    @Test public void colorReadWithoutWriteCannotAuthorizeDiscard() {
        String unsafe = "#version 330 core\n"
                + "void clearSSBOs(){if(gl_FragCoord.x + gl_FragCoord.y < 1.5){}}\n"
                + "void main(){clearSSBOs(); vec4 previous=gl_FragData[0];}\n";
        assertSame(unsafe, ShaderFinalFragmentGuard.transformFragment(unsafe));
    }
    // Actual Oculus 1.8.0 patchComposite output (simple print mode):
    // gl_FragData is renamed, texture2D becomes texture, literals gain f.
    private static final String OCULUS_HIGH = "#version 410 core\n"
            + "layout(location = 0) out vec4 iris_FragData0;\n"
            + "uniform sampler2D colortex3; uniform sampler2D noisetex; in vec2 texCoord;\n"
            + "void main() { vec2 texCoordM = texCoord;\n"
            + "vec3 color = texture(colortex3, texCoordM).rgb;\n"
            + "float dither = texture(noisetex, texCoord).b;\n"
            + "color += vec3((dither - 0.25f) / 128.0f);\n"
            + "iris_FragData0 = vec4(color, 1.0f); }\n";

    @Test public void oculusHighWithoutCompiledOutSsboResetGetsRealUniform() {
        String patched = ShaderFinalFragmentGuard.transformFragment(OCULUS_HIGH);
        assertTrue(patched.contains("uniform vec4 simplymirrors_FinalFragmentRect;"));
        assertTrue(patched.indexOf("discard;") < patched.indexOf("vec2 texCoordM"));
        assertTrue(patched.contains("iris_FragData0 = vec4(color, 1.0f);"));
        assertEquals(patched, ShaderFinalFragmentGuard.transformFragment(patched));
    }

    @Test public void oculusOriginResetStillPreservesOriginWithFloatSuffix() {
        String transformed = PINNED.replace("gl_FragData[0]", "iris_FragData0")
                .replace("1.5)", "1.5f)");
        assertTrue(ShaderFinalFragmentGuard.transformFragment(transformed)
                .contains("uniform vec4 simplymirrors_FinalFragmentRect;"));
    }

    @Test public void pureHighRecognitionRejectsSharedSideEffectsAnywhere() {
        for (String effect : new String[] {
                "layout(std430) buffer State { int value; };",
                "void helper(){ imageStore(sharedImage, ivec2(0), vec4(1)); }",
                "void helper(){ atomicAdd(counter, 1); }",
                "void helper(){ memoryBarrier(); }",
                "void clearSSBOs(){}"
        }) {
            String unsafe = OCULUS_HIGH + effect;
            assertSame(unsafe, ShaderFinalFragmentGuard.transformFragment(unsafe));
        }
    }
}