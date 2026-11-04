"""Checks per-target glue which shared Java tests alone cannot exercise."""
import re
import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
VERSIONS=("1.16.5","1.20.1","1.21.1","26.1.2")


class RendererAdapterContractTests(unittest.TestCase):
    def test_all_adapters_apply_limit_after_visibility(self):
        for version in VERSIONS:
            with self.subTest(version=version):
                source=(ROOT/f"common-{version}/src/main/java/com/nstut/simplymirrors/client/MirrorRenderer.java").read_text()
                finder=source.split("private static List<MirrorSurface> find(",1)[1].split("public static void release()",1)[0]
                self.assertNotRegex(finder,r"\.subList\s*\(")
                self.assertNotRegex(finder,r"(?:out|surfaces)\.size\(\)\s*>\s*(?:1536|16384)")
                self.assertIn("MirrorFaceSelector.select(",source)
                self.assertIn("MirrorFaceSelector.MAX_VISIBLE_FACES",source)

    def test_all_adapters_reset_temporal_history_on_release(self):
        for version in VERSIONS:
            with self.subTest(version=version):
                source=(ROOT/f"common-{version}/src/main/java/com/nstut/simplymirrors/client/MirrorRenderer.java").read_text()
                release=source.split("public static void release()",1)[1]
                self.assertIn("TEMPORAL.reset()",release)
                self.assertIn("release();lastLevel=mc.level",source)
                self.assertIn("TEMPORAL.advance(",source)


if __name__=="__main__":
    unittest.main()
