"""Unit contracts for the reviewed dependency ROI experiment (no native GL)."""
import unittest
from unittest.mock import patch
from pathlib import Path
import volumetric_weight_ci as ci


class DependencyRoiAcceptanceTests(unittest.TestCase):
    def test_roi_excludes_legacy_tail(self):
        self.assertNotIn("--shader-tail-crop",ci.benchmark_command("trial","test","dependency-roi"))
        self.assertIn("--shader-tail-crop",ci.benchmark_command("trial","test"))

    def test_requires_positive_native_activation(self):
        prefix="SIMPLY_MIRRORS_DEPENDENCY_ROI_PROFILE accepted=true\n"
        for log in ("",prefix,prefix+"SIMPLY_MIRRORS_DEPENDENCY_ROI draws=1 pixelsSaved=0"):
            with self.assertRaises(AssertionError):
                ci.require_roi_activation(log,"trial")
        active=prefix+"SIMPLY_MIRRORS_DEPENDENCY_ROI draws=20 pixelsSaved=300"
        self.assertEqual(300,ci.require_roi_activation(active,"trial")["pixelsSaved"])
        with self.assertRaises(AssertionError):
            ci.require_roi_activation(active,"control")
        self.assertFalse(ci.require_roi_activation("","control")["active"])

    def test_native_denial_is_reported(self):
        with self.assertRaisesRegex(AssertionError,"active-ssbo"):
            ci.require_roi_activation("SIMPLY_MIRRORS_DEPENDENCY_ROI_PROFILE accepted=true\nSIMPLY_MIRRORS_DEPENDENCY_ROI_DENIED reason=active-ssbo","trial")

    def test_all_static_and_moving_images_compared(self):
        with patch.object(ci,"compare_capture",return_value={"meanRgbError":0}) as compare:
            result=ci.compare_roi_captures(Path("control"),Path("trial"))
        self.assertEqual(6,compare.call_count)
        self.assertEqual({"static","-parity-motion-a","-parity-motion-b"},set(result["one-mirror"]))

    def test_final_stage_profile_is_required(self):
        with self.assertRaises(AssertionError):
            ci.roi_shader_timings("")
        line="SIMPLY_MIRRORS_SHADER_QUAD_PROFILE program=final samples=100 gpuMedianMs=1.25 gpuP95Ms=2.5 dropped=0"
        self.assertEqual({"final":1.25},ci.roi_shader_timings(line))
