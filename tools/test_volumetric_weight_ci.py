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
        self.assertTrue(ci.require_weight_reuse("SIMPLY_MIRRORS_HISTORY_REDIRECT_ACTIVE textures=8 units=8 attachments=12")["applied"])
