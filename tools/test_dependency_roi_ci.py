"""Unit contracts for the reviewed dependency ROI experiment (no native GL)."""
import unittest
from unittest.mock import patch
from pathlib import Path
import volumetric_weight_ci as ci


class DependencyRoiAcceptanceTests(unittest.TestCase):
    def test_roi_excludes_legacy_tail(self):
        self.assertNotIn("--shader-tail-crop",ci.benchmark_command("trial","test","dependency-roi"))
        self.assertNotIn("--shader-tail-crop",ci.benchmark_command("trial","test"))

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

    def test_zero_density_must_compile_and_activate_only_on_trial(self):
        marker="SIMPLY_MIRRORS_VOLUMETRIC_ZERO_DENSITY program=composite1\n"
        active="SIMPLY_MIRRORS_VOLUMETRIC_ZERO_DENSITY_ACTIVE program=composite1\n"
        pinned="SIMPLY_MIRRORS_DEPENDENCY_ROI_PROFILE accepted=true\n"
        for log in ("",marker,marker+active):
            with self.assertRaises(AssertionError):
                ci.require_zero_density_activation(log,"trial")
        self.assertTrue(ci.require_zero_density_activation(marker+active+pinned,"trial")["active"])
        with self.assertRaises(AssertionError):
            ci.require_zero_density_activation(marker+active+pinned,"control")
        self.assertFalse(ci.require_zero_density_activation("","control")["active"])

    def test_reject_bursty_arm_without_launching_counterpart(self):
        from tail_crop_ci import assert_stable_arm, UnstableBenchmarkError
        good={"medianAvgFps":72.0,"medianP95FrameMs":14.0,"medianP99FrameMs":18.0}
        stages={label:dict(good) for label in ("no-mirror","one-mirror","nine-coplanar-mirrors")}
        assert_stable_arm("trial",{"stages":stages},[])
        stages["no-mirror"]["medianP99FrameMs"]=158.0
        with self.assertRaisesRegex(UnstableBenchmarkError,"frame-time bursts"):
            assert_stable_arm("trial",{"stages":stages},[])

    def test_raw_capture_archive_is_bounded(self):
        from tempfile import TemporaryDirectory
        with TemporaryDirectory() as folder:
            root=Path(folder)
            source=root/"client"/"fps-evidence"
            source.mkdir(parents=True)
            for name in ("scene-7.json","scene-8-parity-motion-a.png","scene-9.png","unrelated.txt"):
                (source/name).write_bytes(b"fixture")
            evidence=root/"run-evidence"
            result=ci.archive_raw_captures(source,evidence)
            self.assertEqual({"captures/scene-7.json","captures/scene-8-parity-motion-a.png","captures/scene-9.png"},set(result))
            self.assertTrue((evidence/"capture-index.json").is_file())
            self.assertFalse((evidence/"captures"/"unrelated.txt").exists())

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
