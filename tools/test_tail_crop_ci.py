"""Offline contracts for the one-shot native GPU A/B acceptance gate."""
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import TestCase

from PIL import Image, ImageDraw

from tail_crop_ci import compare_capture, assert_stable_pair, UnstableBenchmarkError, benchmark_command, profile_medians


class TailCropImageAcceptanceTests(TestCase):
    def test_identical_full_resolution_captures_pass(self):
        with TemporaryDirectory() as directory:
            a, b = Path(directory) / "a.png", Path(directory) / "b.png"
            Image.new("RGB", (1920, 1080), (80, 100, 120)).save(a)
            Image.new("RGB", (1920, 1080), (80, 100, 120)).save(b)
            for scene in (8, 9):
                result = compare_capture(a, b, scene)
                self.assertEqual(0., result["meanRgbError"])
                self.assertEqual(0., result["peak32RgbError"])

    def test_localized_mirror_corruption_fails_even_with_low_frame_average(self):
        with TemporaryDirectory() as directory:
            a, b = Path(directory) / "a.png", Path(directory) / "b.png"
            Image.new("RGB", (1920, 1080), (80, 100, 120)).save(a)
            changed = Image.new("RGB", (1920, 1080), (80, 100, 120))
            ImageDraw.Draw(changed).rectangle((890, 500, 922, 532),
                                              fill=(255, 0, 0))
            changed.save(b)
            with self.assertRaisesRegex(AssertionError, "damages scene"):
                compare_capture(a, b, 8)

    def test_both_identical_but_undersized_captures_fail_exact_1080p(self):
        with TemporaryDirectory() as directory:
            a, b = Path(directory) / "a.png", Path(directory) / "b.png"
            Image.new("RGB", (1920, 1061)).save(a)
            Image.new("RGB", (1920, 1061)).save(b)
            with self.assertRaisesRegex(AssertionError, "exact native"):
                compare_capture(a, b, 8)

    def test_capture_dimensions_must_match(self):
        with TemporaryDirectory() as directory:
            a, b = Path(directory) / "a.png", Path(directory) / "b.png"
            Image.new("RGB", (1920, 1080)).save(a)
            Image.new("RGB", (1600, 900)).save(b)
            with self.assertRaisesRegex(AssertionError, "resolution changed"):
                compare_capture(a, b, 9)


class TailCropEnvironmentStabilityTests(TestCase):
    @staticmethod
    def summary(no_mirror: float, one: float, nine: float) -> dict:
        return {"stages": {
            "no-mirror": {"medianAvgFps": no_mirror},
            "one-mirror": {"medianAvgFps": one},
            "nine-coplanar-mirrors": {"medianAvgFps": nine},
        }}

    def test_real_second_pair_no_mirror_drift_is_rejected(self):
        trial = self.summary(76.070, 42.834, 40.135)
        control = self.summary(72.312, 39.901, 21.429)
        with self.assertRaisesRegex(UnstableBenchmarkError, "No-mirror A/B drift"):
            assert_stable_pair(control, trial, [])

    def test_contended_nine_mirror_control_rejected_even_if_neutral_is_stable(self):
        reference = {"control": {"no-mirror": 76.4, "one-mirror": 40.8,
                                 "nine-coplanar-mirrors": 39.1},
                     "trial": {"no-mirror": 76.6, "one-mirror": 42.8,
                               "nine-coplanar-mirrors": 40.2}}
        control = self.summary(76.1, 40.5, 21.429)
        trial = self.summary(76.2, 42.7, 40.1)
        with self.assertRaisesRegex(UnstableBenchmarkError, "control nine-coplanar-mirrors"):
            assert_stable_pair(control, trial, [reference])

    def test_contended_trial_scene_rejected_even_if_neutral_is_stable(self):
        reference = {"control": {"no-mirror": 76.4, "one-mirror": 40.8,
                                 "nine-coplanar-mirrors": 39.1},
                     "trial": {"no-mirror": 76.6, "one-mirror": 42.8,
                               "nine-coplanar-mirrors": 40.2}}
        control = self.summary(76.1, 40.5, 39.0)
        trial = self.summary(76.2, 42.7, 21.429)
        with self.assertRaisesRegex(UnstableBenchmarkError, "trial nine-coplanar-mirrors"):
            assert_stable_pair(control, trial, [reference])

    def test_stable_pair_preserves_existing_no_mirror_limit(self):
        reference = {"control": {"no-mirror": 76.4, "one-mirror": 40.8,
                                 "nine-coplanar-mirrors": 39.1},
                     "trial": {"no-mirror": 76.6, "one-mirror": 42.8,
                               "nine-coplanar-mirrors": 40.2}}
        control = self.summary(75.8, 40.2, 38.8)
        trial = self.summary(76.0, 42.2, 39.7)
        self.assertAlmostEqual(0.264, assert_stable_pair(control, trial, [reference]))


class NativeCopyImageOptimizationTests(TestCase):
    def test_trial_preserves_1080p_camera_and_enables_final_dual_experiment(self):
        args = benchmark_command("trial", "tail-benchmark-trial")
        self.assertIn("--shader-tail-crop", args)
        self.assertIn("--history-copy-image", args)
        self.assertIn("--shader-final-dual-crop", args)
        self.assertNotIn("--shader-early-composite-crop", args)
        self.assertIn("--moving-camera", args)
        self.assertIn("--profile-gpu", args)
        self.assertEqual("1920", args[args.index("--width") + 1])
        self.assertEqual("1080", args[args.index("--height") + 1])
        self.assertNotIn("--shader-final-origin-crop", args)

    def test_control_retains_accepted_gpu_baseline_without_final_dual_crop(self):
        args = benchmark_command("control", "tail-benchmark-control")
        self.assertIn("--shader-tail-crop", args)
        self.assertIn("--history-copy-image", args)
        self.assertNotIn("--shader-early-composite-crop", args)
        self.assertNotIn("--shader-final-dual-crop", args)
        self.assertNotIn("--shader-final-origin-crop", args)
        self.assertIn("--moving-camera", args)


class NativeStageTelemetryTests(TestCase):
    def test_median_real_cpu_gpu_stage_timings(self):
        line_a = ("[timestamp] SIMPLY_MIRRORS_GPU_PROFILE frames=90 "
                  "gpuCaptureMs=0.400 gpuWorldMs=5.000 gpuRestoreMs=0.350 "
                  "gpuFinalizeMs=2.600 cpuCaptureMs=1.200 cpuWorldMs=7.200 "
                  "cpuRestoreMs=0.900 cpuFinalizeMs=3.000")
        line_b = ("SIMPLY_MIRRORS_GPU_PROFILE frames=180 "
                  "gpuCaptureMs=0.200 gpuWorldMs=4.000 gpuRestoreMs=0.250 "
                  "gpuFinalizeMs=2.400 cpuCaptureMs=1.000 cpuWorldMs=6.000 "
                  "cpuRestoreMs=0.700 cpuFinalizeMs=2.500")
        got = profile_medians(line_a + "\n" + line_b)
        self.assertAlmostEqual(0.3, got["gpuCaptureMs"])
        self.assertAlmostEqual(4.5, got["gpuWorldMs"])
        self.assertAlmostEqual(0.3, got["gpuRestoreMs"])
        self.assertAlmostEqual(1.1, got["cpuCaptureMs"])
        self.assertAlmostEqual(2.75, got["cpuFinalizeMs"])

    def test_invalid_missing_or_unsupported_stage_timings_are_not_misreported(self):
        logs = ("SIMPLY_MIRRORS_GPU_PROFILE frames=90 "
                "gpuCaptureMs=-1.0 gpuRestoreMs=10000 cpuWorldMs=8.0")
        got = profile_medians(logs)
        self.assertEqual({"cpuWorldMs": 8.0}, got)
        self.assertEqual({}, profile_medians("normal game log with no GPU profiling"))

class FinalOriginTelemetryTests(TestCase):
    def test_gpu_shadow_stage_is_included_for_root_cause_comparison(self):
        text = ("SIMPLY_MIRRORS_GPU_PROFILE frames=90 "
                "gpuShadowsAndPrepareMs=3.125 gpuFinalizeMs=6.451 "
                "gpuWorldMs=10.900 gpuCaptureMs=0.700 gpuRestoreMs=0.650")
        got = profile_medians(text)
        self.assertAlmostEqual(3.125, got["gpuShadowsAndPrepareMs"])

class BenchmarkFailureClassificationTests(TestCase):
    def test_unstable_pair_cannot_be_mislabeled_as_pixel_corruption(self):
        import os
        from unittest.mock import patch
        import tail_crop_ci
        stages = {name: {"medianAvgFps": fps} for name, fps in (
            ("no-mirror", 70.0), ("one-mirror", 40.0),
            ("nine-coplanar-mirrors", 36.0))}
        fake = ({"stages": stages}, Path("not-read-images"), Path("not-read-summary"))
        with (patch.object(tail_crop_ci.os, "name", "nt"),
              patch.dict(os.environ, {"CI_GRAPHICS_AGENT": "1"}),
              patch.object(tail_crop_ci, "run_variant", return_value=fake),
              patch.object(tail_crop_ci, "assert_stable_pair",
                           side_effect=UnstableBenchmarkError("GPU contended")),
              patch.object(tail_crop_ci, "compare_capture") as comparison):
            with self.assertRaises(UnstableBenchmarkError):
                tail_crop_ci.accept()
            # Both attempts were rejected before opening any captured images.
            self.assertEqual(4, tail_crop_ci.run_variant.call_count)
            comparison.assert_not_called()
