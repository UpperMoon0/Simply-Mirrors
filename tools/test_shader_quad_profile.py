"""Contracts for nonblocking per-program native GPU shader profiling."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "common-client/src/main/java/com/nstut/simplymirrors/client"


class ShaderQuadProfilerContractTest(unittest.TestCase):
    def test_instrumentation_is_profile_only(self):
        source = (SRC / "ShaderQuadGpuProfiler.java").read_text()
        self.assertIn('Boolean.getBoolean("simplymirrors.gpuProfile")', source)
        self.assertIn('MirrorRenderer.redirect == null', source)
        self.assertIn("GL_QUERY_RESULT_AVAILABLE", source)
        self.assertIn("PENDING.size() > 2048", source)
        self.assertIn("glDeleteQueries(start)", source)
        self.assertIn("glDeleteQueries(end)", source)

    def test_quad_boundaries_and_lifecycle_are_connected(self):
        mixin = (SRC / "mixin/IrisFullscreenMixin.java").read_text()
        profiler = (SRC / "MirrorGpuProfiler.java").read_text()
        scissor = (SRC / "ReflectionScissor.java").read_text()
        self.assertIn("ShaderQuadGpuProfiler.begin();", mixin)
        self.assertIn("ShaderQuadGpuProfiler.end();", mixin)
        self.assertIn('method="renderQuad",at=@At("RETURN")', mixin)
        self.assertIn("ShaderQuadGpuProfiler.poll();", profiler)
        self.assertIn("ShaderQuadGpuProfiler.reset();", profiler)
        self.assertIn("profileProgramName(int program)", scissor)


if __name__ == "__main__":
    unittest.main()