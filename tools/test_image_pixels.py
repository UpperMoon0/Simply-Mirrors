import unittest
from PIL import Image
from image_pixels import flattened_pixels


class FlattenedPixelsCompatibilityTests(unittest.TestCase):
    def test_rgb_image_matches_raw_pixels(self):
        image = Image.new("RGB", (2, 1))
        image.putpixel((0, 0), (1, 2, 3))
        image.putpixel((1, 0), (4, 5, 6))
        self.assertEqual([(1, 2, 3), (4, 5, 6)], list(flattened_pixels(image)))

    def test_12_0_fallback_without_deprecation_warning(self):
        class Pillow12:
            def getdata(self):
                import warnings
                warnings.warn("Image.Image.getdata is deprecated", DeprecationWarning)
                return [(2, 3, 4)]
        import warnings
        with warnings.catch_warnings():
            warnings.simplefilter("error", DeprecationWarning)
            self.assertEqual([(2, 3, 4)], list(flattened_pixels(Pillow12())))
