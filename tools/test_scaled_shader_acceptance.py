"""Regression tests for half-resolution Iris shader acceptance fixture."""
from pathlib import Path
import tempfile
import unittest
import zipfile

from scaled_shader_acceptance import compare_images, make_scaled_pack


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
            self.assertAlmostEqual(0, compare_images(a, b)["mean_abs_error"])
            ImageDraw.Draw(image).rectangle((300, 130, 500, 330), fill=(0, 0, 0))
            image.save(b)
            with self.assertRaisesRegex(AssertionError, "capture mismatch"):
                compare_images(a, b)


if __name__ == "__main__":
    unittest.main()
