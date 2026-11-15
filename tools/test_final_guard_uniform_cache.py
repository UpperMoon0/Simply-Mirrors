"""Source contract: linked-program uniform locations must not leak across relinks."""
from pathlib import Path
import unittest

SOURCE = (Path(__file__).resolve().parents[1] /
          "common-client/src/main/java/com/nstut/simplymirrors/client/ReflectionScissor.java").read_text()

class UniformCacheContract(unittest.TestCase):
    def test_uniform_location_is_reused(self):
        self.assertIn("cachedLocation != null ? cachedLocation :", SOURCE)
        self.assertIn("FINAL_GUARD_UNIFORMS.put(program, location)", SOURCE)

    def test_relink_and_delete_invalidate_cache(self):
        self.assertIn("PROGRAM_NAMES.put(program, name); FINAL_GUARD_UNIFORMS.remove(program)", SOURCE)
        self.assertIn("PROGRAM_NAMES.remove(program);\n        FINAL_GUARD_UNIFORMS.remove(program)", SOURCE.replace("\r\n", "\n"))

if __name__ == "__main__":
    unittest.main()
