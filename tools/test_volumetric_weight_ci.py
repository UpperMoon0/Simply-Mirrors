import os
import unittest
from unittest.mock import patch
import volumetric_weight_ci as ci
import fps_benchmark
class VolumetricWeightTest(unittest.TestCase):
    def test_cloud_experiment_excludes_rejected_arithmetic(self):
        for label in ("control", "trial"):
            command=ci.benchmark_command(label,"proof")
            self.assertNotIn("--shader-volumetric-weight-reuse",command)
            self.assertNotIn("--shader-volumetric-shadow-ray",command)
            self.assertNotIn("--shader-final-fragment-guard",command)
        with patch.dict(os.environ,{"SIMPLY_MIRRORS_CLOUD_OCCLUSION":"0"}):
            self.assertNotIn("shaderCloudOcclusion=true",fps_benchmark.graphics_java_options())
        with patch.dict(os.environ,{"SIMPLY_MIRRORS_CLOUD_OCCLUSION":"1"}):
            self.assertIn("shaderCloudOcclusion=true",fps_benchmark.graphics_java_options())
    def test_history_redirection_is_opt_in(self):
        with patch.dict(os.environ,{"SIMPLY_MIRRORS_HISTORY_REDIRECT":"0"}):
            self.assertNotIn("historyTextureRedirect=true",fps_benchmark.graphics_java_options())
        with patch.dict(os.environ,{"SIMPLY_MIRRORS_HISTORY_REDIRECT":"1"}):
            self.assertIn("historyTextureRedirect=true",fps_benchmark.graphics_java_options())
    def test_native_transform_is_required(self):
        with self.assertRaises(AssertionError): ci.require_weight_reuse("")
        history="SIMPLY_MIRRORS_HISTORY_REDIRECT_ACTIVE textures=8 units=8 attachments=12"
        with self.assertRaises(AssertionError): ci.require_weight_reuse(history)
        self.assertTrue(ci.require_weight_reuse(history+"\nSIMPLY_MIRRORS_DISCARD_FINAL_HISTORY copies=3")["applied"])
    def test_final_history_discard_is_opt_in(self):
        with patch.dict(os.environ,{"SIMPLY_MIRRORS_DISCARD_FINAL_HISTORY":"0"}):
            self.assertNotIn("discardFinalHistory=true",fps_benchmark.graphics_java_options())
        with patch.dict(os.environ,{"SIMPLY_MIRRORS_DISCARD_FINAL_HISTORY":"1"}):
            self.assertIn("discardFinalHistory=true",fps_benchmark.graphics_java_options())

    def acceptance_pairs(self, control, trial, improvement=3.5):
        def stages(capture, world, restore):
            return {"gpuCaptureMs": capture, "gpuWorldMs": world,
                    "gpuRestoreMs": restore, "gpuFinalizeMs": 4.9}
        return [{"fpsChangePct": {"one-mirror": improvement,
                                  "nine-coplanar-mirrors": 0.0},
                 "stagesMs": {"control": stages(*control), "trial": stages(*trial)}}
                for _ in range(3)]
    def test_transfer_savings_can_pay_for_world_increase(self):
        result=ci.evaluate_gpu_acceptance(
            self.acceptance_pairs((.72,9.0,.65),(.44,9.12,.017)))
        self.assertLess(result["medianGpuCaptureWorldRestoreMs"]["trial"],
                        result["medianGpuCaptureWorldRestoreMs"]["control"])
        self.assertGreater(result["medianGpuWorldMs"]["trial"],
                           result["medianGpuWorldMs"]["control"])
    def test_total_regression_rejected_even_when_world_faster(self):
        with self.assertRaises(AssertionError):
            ci.evaluate_gpu_acceptance(
                self.acceptance_pairs((.1,9.0,.1),(.8,8.8,.8)))
    def test_three_percent_fps_gate_is_unchanged(self):
        with self.assertRaises(AssertionError):
            ci.evaluate_gpu_acceptance(
                self.acceptance_pairs((.72,9.0,.65),(.44,9.12,.017),2.99))
