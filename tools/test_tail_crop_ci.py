"""Offline contracts for the one-shot native GPU A/B acceptance gate."""
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import TestCase

from PIL import Image, ImageDraw

from tail_crop_ci import compare_capture


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

    def test_capture_dimensions_must_match(self):
        with TemporaryDirectory() as directory:
            a, b = Path(directory) / "a.png", Path(directory) / "b.png"
            Image.new("RGB", (1920, 1080)).save(a)
            Image.new("RGB", (1600, 900)).save(b)
            with self.assertRaisesRegex(AssertionError, "resolution changed"):
                compare_capture(a, b, 9)
