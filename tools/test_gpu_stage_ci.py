"""Verify GPU probes never run in an unqualified graphics session."""
import unittest
from visual_smoke import should_profile_gpu, gpu_profile_lines

class GpuStageQualificationTests(unittest.TestCase):
    def test_requires_trusted_shader_worker_and_forge_1201(self):
        self.assertTrue(should_profile_gpu("forge-1.20.1", True, True, True))
        for case in [
            ("forge-1.20.1", True, False, True),
            ("forge-1.20.1", True, True, False),
            ("forge-1.20.1", False, True, True),
            ("fabric-1.20.1", True, True, True),
            ("forge-1.16.5", True, True, True),
        ]:
            self.assertFalse(should_profile_gpu(*case))

    def test_profile_collector_filters_client_log(self):
        log = ("render started\nSIMPLY_MIRRORS_GPU_PROFILE frames=90 gpuWorldMs=11.31\n"
               "not a profile\nSIMPLY_MIRRORS_GPU_PROFILE frames=180 gpuWorldMs=9.20\n")
        self.assertEqual(2, len(gpu_profile_lines(log)))
        self.assertEqual([], gpu_profile_lines("no samples"))
