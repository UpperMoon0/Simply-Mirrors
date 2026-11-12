"""Reject false positive shader-guard A/B claims without a native GPU."""
import unittest
from unittest.mock import patch

import final_fragment_ci
import fps_benchmark


class FinalFragmentCiTest(unittest.TestCase):
    def test_exact_trial_only_switch(self):
        trial = final_fragment_ci.benchmark_command("trial", "proof")
        control = final_fragment_ci.benchmark_command("control", "proof")
        self.assertIn("--shader-final-fragment-guard", trial)
        self.assertNotIn("--shader-final-fragment-guard", control)
        self.assertIn("--shader-tail-crop", control)
        self.assertIn("--history-copy-image", control)
        self.assertIn("--moving-camera", control)

    def test_gpu_option_is_explicit_and_disabled_by_default(self):
        self.assertNotIn("shaderFinalFragmentGuard", fps_benchmark.graphics_java_options())
        self.assertIn("-Dsimplymirrors.shaderFinalFragmentGuard=true",
                      fps_benchmark.graphics_java_options(shader_final_fragment_guard=True))

    def test_reject_missing_runtime_shaders(self):
        with self.assertRaisesRegex(AssertionError, "No native"):
            final_fragment_ci.require_instrumented_final("")
        with self.assertRaisesRegex(AssertionError, "did not apply"):
            final_fragment_ci.require_instrumented_final(
                "SIMPLY_MIRRORS_FINAL_FRAGMENT_GUARD attempts=500 applied=0 missingUniform=500")

    def test_accept_native_instrumentation(self):
        result = final_fragment_ci.require_instrumented_final(
            "SIMPLY_MIRRORS_FINAL_FRAGMENT_GUARD attempts=180 applied=175 missingUniform=0\n"
            "SIMPLY_MIRRORS_FINAL_FRAGMENT_GUARD attempts=360 applied=350 missingUniform=0")
        self.assertEqual(350, result["applied"])

    def test_reject_missing_uniform_even_when_other_shaders_apply(self):
        with self.assertRaisesRegex(AssertionError, "missing=2"):
            final_fragment_ci.require_instrumented_final(
                "SIMPLY_MIRRORS_FINAL_FRAGMENT_GUARD attempts=200 applied=180 missingUniform=2")

    def test_reject_non_monotonic_gpu_instrumentation(self):
        with self.assertRaisesRegex(AssertionError, "regressed"):
            final_fragment_ci.require_instrumented_final(
                "SIMPLY_MIRRORS_FINAL_FRAGMENT_GUARD attempts=300 applied=280 missingUniform=0\n"
                "SIMPLY_MIRRORS_FINAL_FRAGMENT_GUARD attempts=200 applied=190 missingUniform=0")
    def test_gpu_stage_savings_are_signed_and_stage_specific(self):
        control = dict.fromkeys(("gpuCaptureMs", "gpuWorldMs",
                                 "gpuFinalizeMs", "gpuRestoreMs"), 5.0)
        trial = dict(control, gpuFinalizeMs=3.5, gpuWorldMs=5.2)
        result = final_fragment_ci.stage_savings_ms(control, trial)
        self.assertEqual(1.5, result["gpuFinalizeMs"])
        self.assertEqual(-0.2, result["gpuWorldMs"])

    def test_gpu_stage_savings_reject_missing_and_nonfinite_samples(self):
        control = dict.fromkeys(("gpuCaptureMs", "gpuWorldMs",
                                 "gpuFinalizeMs", "gpuRestoreMs"), 5.0)
        for bad in (float("nan"), float("inf"), -1, None):
            with self.subTest(bad=bad):
                with self.assertRaisesRegex(AssertionError, "gpuFinalizeMs"):
                    final_fragment_ci.stage_savings_ms(
                        control, dict(control, gpuFinalizeMs=bad))

    def test_three_pair_acceptance_uses_requested_three_percent_gate(self):
        def pair(one, nine, finalize):
            return {"fpsChangePct": {"one-mirror": one,
                                     "nine-coplanar-mirrors": nine},
                    "stagesMs": {"control": {"gpuFinalizeMs": 4.9},
                                 "trial": {"gpuFinalizeMs": finalize}}}
        pairs = [pair(3.1, -1.5, 4.0), pair(3.2, -1.0, 4.1),
                 pair(2.9, -2.1, 4.2)]
        result = final_fragment_ci.evaluate_gpu_acceptance(pairs)
        self.assertEqual(3.1, result["medianOneMirrorImprovementPct"])
        with self.assertRaisesRegex(AssertionError, "Insufficient"):
            final_fragment_ci.evaluate_gpu_acceptance(
                [pair(2.0, 0, 4.0)] * 3)
        with self.assertRaisesRegex(AssertionError, "Three stable"):
            final_fragment_ci.evaluate_gpu_acceptance(pairs[:2])
        with self.assertRaisesRegex(AssertionError, "Insufficient"):
            final_fragment_ci.evaluate_gpu_acceptance(
                [pair(3.5, -2.1, 4.0)] * 3)
        with self.assertRaisesRegex(AssertionError, "Insufficient"):
            final_fragment_ci.evaluate_gpu_acceptance(
                [pair(3.5, 0, 5.0)] * 3)

    def test_reject_ambiguous_settings(self):
        summary = {
            "shaderTailCropEnabled": True,
            "historyCopyImageEnabled": True,
            "shaderFinalOriginCropEnabled": False,
            "shaderEarlyCompositeCropEnabled": False,
            "shaderFinalDualCropEnabled": False,
            "frustumCropEnabled": False,
            "apertureCullEnabled": False,
            "resolution": "1920x1080",
            "renderDistance": 12,
            "movingCameraEnabled": True,
            "shaderFinalFragmentGuardEnabled": False
        }
        final_fragment_ci.verify_settings(summary, "control")
        with self.assertRaisesRegex(AssertionError, "shaderFinalFragmentGuardEnabled"):
            final_fragment_ci.verify_settings(summary, "trial")


if __name__ == "__main__":
    unittest.main()
