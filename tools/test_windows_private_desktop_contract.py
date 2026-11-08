"""Safety regression: Windows private-desktop CI uses its own session and token."""
import os
from pathlib import Path
import subprocess
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
HARNESS = ROOT / "tools/visual_smoke.py"


class InteractiveGraphicsContractTests(unittest.TestCase):
    def test_harness_advertises_private_desktop_mode(self):
        result = subprocess.run(
            [sys.executable, str(HARNESS), "--help"],
            cwd=ROOT, capture_output=True, text=True, timeout=15)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("--interactive-offscreen", result.stdout)

    def test_graphics_flag_fails_closed_without_reviewed_windows_agent(self):
        if os.name == "nt":
            self.skipTest("Mode requires an interactive agent; unit host cannot pretend to be one")
        env = os.environ.copy()
        env["CI_GRAPHICS_AGENT"] = "1"
        result = subprocess.run(
            [sys.executable, str(HARNESS), "--target", "forge-1.20.1",
             "--interactive-offscreen"],
            cwd=ROOT, env=env, capture_output=True, text=True, timeout=15)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("reviewed native Windows graphics agent", result.stderr)

    def test_native_spawn_never_brokers_into_user_desktop(self):
        script = HARNESS.read_text(encoding="utf-8")
        self.assertIn('os.environ.get("CI_GRAPHICS_AGENT") != "1"', script)
        self.assertIn("SIMPLY_MIRRORS_VISUAL_PRIVATE_DESKTOP", script)
        self.assertNotIn("WTSQueryUserToken", script)
        self.assertNotIn("CreateProcessAsUser(", script)
        self.assertNotIn("from fps_benchmark import new_interactive_client", script)


if __name__ == "__main__":
    unittest.main()
