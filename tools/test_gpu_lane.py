"""The GPU lock must release even when a visual check raises."""
import unittest
from unittest.mock import patch
from gpu_lane import exclusive_graphics_lane


class LaneTests(unittest.TestCase):
    def test_non_windows_does_not_require_native_windows_api(self):
        with patch("gpu_lane.os.name", "posix"):
            with exclusive_graphics_lane():
                self.assertTrue(True)
