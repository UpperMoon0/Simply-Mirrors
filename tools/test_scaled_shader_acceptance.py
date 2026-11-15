"""Regression tests for half-resolution Iris shader acceptance fixture."""
from pathlib import Path
import tempfile
import unittest
import zipfile

from scaled_shader_acceptance import compare_images, make_scaled_pack, projected_fixture_roi


class ScaledShaderFixtureTests(unittest.TestCase):
    def test_only_scaled_buffers_are_modified_in_disposable_pack(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source, dest = root / "pinned.zip", root / "scaled.zip"
            shader = ("# options\n    size.buffer.colortex1 = REFLECTION_RES REFLECTION_RES\n"
                      "    size.buffer.colortex7 = REFLECTION_RES REFLECTION_RES\n")
            with zipfile.ZipFile(source, "w") as z:
                z.writestr("shaders/shaders.properties", shader)
                z.writestr("shaders/gbuffers_terrain.vsh", "unchanged\n")
            original = source.read_bytes()
            report = make_scaled_pack(source, dest)
            self.assertEqual(original, source.read_bytes())
            self.assertNotEqual(report["source_sha256"], report["scaled_sha256"])
            with zipfile.ZipFile(dest) as z:
                self.assertIn("size.buffer.colortex1 = 0.5 0.5", z.read("shaders/shaders.properties").decode())
                self.assertIn("size.buffer.colortex7 = 0.5 0.5", z.read("shaders/shaders.properties").decode())
                self.assertEqual(b"unchanged\n", z.read("shaders/gbuffers_terrain.vsh"))

    def test_fixture_fails_closed_if_pinned_shader_layout_changed(self):
        with tempfile.TemporaryDirectory() as tmp:
            source, dest = Path(tmp) / "invalid.zip", Path(tmp) / "new.zip"
            with zipfile.ZipFile(source, "w") as z:
                z.writestr("shaders/shaders.properties", "size.buffer.colortex1 = 1.0 1.0\n")
            with self.assertRaisesRegex(ValueError, "Unexpected shader declaration"):
                make_scaled_pack(source, dest)

    def test_image_comparison_detects_scaled_framebuffer_cropping(self):
        from PIL import Image, ImageDraw
        with tempfile.TemporaryDirectory() as tmp:
            a, b = Path(tmp) / "a.png", Path(tmp) / "b.png"
            image = Image.new("RGB", (800, 450), (20, 30, 40))
            ImageDraw.Draw(image).rectangle((300, 130, 500, 330), fill=(80, 160, 220))
            image.save(a)
            image.save(b)
            self.assertAlmostEqual(0, compare_images(a, b, 0, 70)["mean_abs_error"])
            ImageDraw.Draw(image).rectangle((300, 130, 500, 330), fill=(0, 0, 0))
            image.save(b)
            with self.assertRaisesRegex(AssertionError, "capture mismatch"):
                compare_images(a, b, 0, 70)


    def test_localized_corruption_in_mirror_or_edge_must_fail(self):
        from PIL import Image, ImageDraw
        with tempfile.TemporaryDirectory() as tmp:
            a, b = Path(tmp) / "a.png", Path(tmp) / "b.png"
            size = (960, 540)
            roi = projected_fixture_roi(0, size, 70)
            Image.new("RGB", size, (75, 110, 130)).save(a)
            Image.new("RGB", size, (75, 110, 130)).save(b)
            self.assertEqual(0, compare_images(a, b, 0, 70)["mean_abs_error"])
            for rectangle in [
                (roi[0] + 40, roi[1] + 40, roi[0] + 120, roi[1] + 120),
                # Thin corrupt border near the projected mirror edge, diluted
                # by the old 40% x 53% ROI and mean-only comparison.
                (roi[2] - 40, roi[1] + 45, roi[2] - 32, roi[1] + 130),
            ]:
                image = Image.open(a).copy()
                ImageDraw.Draw(image).rectangle(rectangle, fill=(255, 0, 0))
                image.save(b)
                with self.subTest(rectangle=rectangle), self.assertRaisesRegex(
                        AssertionError, "localized capture mismatch"):
                    compare_images(a, b, 0, 70)

    def test_projected_roi_follows_off_center_fixture(self):
        from PIL import Image, ImageDraw
        with tempfile.TemporaryDirectory() as tmp:
            a, b = Path(tmp) / "a.png", Path(tmp) / "b.png"
            size = (960, 540)
            shifted_eye = (2.0, 66.62, -3.5)
            center = projected_fixture_roi(0, size, 70)
            shifted = projected_fixture_roi(0, size, 70, shifted_eye)
            self.assertNotEqual(center, shifted)
            self.assertGreater(shifted[2], size[0] * .70)
            Image.new("RGB", size, (50, 70, 90)).save(a)
            image = Image.open(a).copy()
            ImageDraw.Draw(image).rectangle(
                (shifted[2]-65, shifted[1]+50, shifted[2]-15, shifted[1]+100),
                fill=(220, 30, 40))
            image.save(b)
            with self.assertRaisesRegex(AssertionError, "localized capture mismatch"):
                compare_images(a, b, 0, 70, shifted_eye)

    def test_all_six_faces_have_visible_projected_bounds(self):
        for face in range(6):
            with self.subTest(face=face):
                box = projected_fixture_roi(face, (960, 540), 70)
                self.assertTrue(0 <= box[0] < box[2] <= 960, box)
                self.assertTrue(0 <= box[1] < box[3] <= 540, box)


if __name__ == "__main__":
    unittest.main()
