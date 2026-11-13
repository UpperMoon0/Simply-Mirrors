import unittest
import volumetric_weight_ci as ci
import fps_benchmark
class VolumetricWeightTest(unittest.TestCase):
    def test_only_trial_enables_weight_reuse_and_keeps_guard_off(self):
        control=ci.benchmark_command("control","proof")
        trial=ci.benchmark_command("trial","proof")
        self.assertNotIn("--shader-volumetric-weight-reuse",control)
        self.assertIn("--shader-volumetric-weight-reuse",trial)
        self.assertNotIn("--shader-volumetric-shadow-ray",control)
        self.assertIn("--shader-volumetric-shadow-ray",trial)
        self.assertNotIn("shaderVolumetricShadowRay=true",fps_benchmark.graphics_java_options())
        self.assertIn("shaderVolumetricShadowRay=true",fps_benchmark.graphics_java_options(shader_volumetric_shadow_ray=True))
        self.assertNotIn("--shader-final-fragment-guard",trial)
        self.assertIn("shaderVolumetricWeightReuse=true",fps_benchmark.graphics_java_options(shader_volumetric_weight_reuse=True))
    def test_native_transform_is_required(self):
        with self.assertRaises(AssertionError): ci.require_weight_reuse("")
        self.assertTrue(ci.require_weight_reuse("SIMPLY_MIRRORS_VOLUMETRIC_WEIGHT_REUSE program=composite1\nSIMPLY_MIRRORS_VOLUMETRIC_SHADOW_RAY program=composite1\nSIMPLY_MIRRORS_VOLUMETRIC_REFLECTION_SCOPE program=composite1")["applied"])
