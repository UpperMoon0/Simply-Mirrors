"""Offline regressions for full ATM10 Sky fixture and artifact provenance."""
import json
from pathlib import Path
import tempfile
import unittest

from atm10_sky import read_lock, safe_override, validate_url
from atm10_sky_visual import shift_command
from visual_smoke import fixture


ROOT = Path(__file__).resolve().parents[1]


class Atm10SkyTest(unittest.TestCase):
    def test_exact_lock_shape(self):
        lock = read_lock(ROOT / "tools/atm10-sky/lock-2.3.1.json")
        self.assertEqual(len(lock["files"]), 337)
        self.assertEqual(lock["neoforge"], "21.1.250")
        self.assertEqual(len({row["file_id"] for row in lock["files"]}), 337)
        names = {row["filename"] for row in lock["files"]}
        self.assertIn("EuphoriaPatcher-1.10.5-r5.9.3-neoforge.jar", names)
        self.assertIn("iris-neoforge-1.8.12+mc1.21.1.jar", names)
        self.assertIn("sodium-neoforge-0.6.13+mc1.21.1.jar", names)

    def test_disallow_untrusted_artifacts(self):
        for url in ("http://edge.forgecdn.net/files/1/2/a.jar",
                    "https://evil.example/files/1/2/a.jar",
                    "file:///tmp/minecraft.jar"):
            with self.subTest(url=url), self.assertRaises(ValueError):
                validate_url(url)

    def test_disallow_unsafe_override(self):
        self.assertEqual(safe_override("overrides/config/example.toml"),
                         Path("config/example.toml"))
        self.assertIsNone(safe_override("manifest.json"))
        for name in ("overrides/../saves/level.dat",
                     "overrides/saves/important.dat",
                     "overrides/config/../../secret",
                     "overrides/logs/latest.log",
                     "overrides/C:/windows/file"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                safe_override(name)

    def test_all_faces_shift_without_touching_absolute_yz(self):
        for scene in range(6):
            with self.subTest(scene=scene):
                commands = fixture(scene, enclosed=True)
                self.assertGreater(len(commands), 10)
                translated = [shift_command(command, 48 * scene) for command in commands]
                self.assertTrue(all(command.startswith(("fill ", "setblock "))
                                    for command in translated))
                for raw, moved in zip(commands, translated):
                    a, b = raw.split(), moved.split()
                    self.assertEqual(int(b[1]) - int(a[1]), 48 * scene)
                    self.assertEqual(a[2:4], b[2:4])
                    if a[0] == "fill":
                        self.assertEqual(int(b[4]) - int(a[4]), 48 * scene)
                        self.assertEqual(a[5:], b[5:])
                    else:
                        self.assertEqual(a[4:], b[4:])

    def test_reject_malformed_fixture(self):
        for command in ("stop", "op @a", "tp @a 0 0 0"):
            with self.assertRaises(ValueError):
                shift_command(command, 48)


if __name__ == "__main__":
    unittest.main()
