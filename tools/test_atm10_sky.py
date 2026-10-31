"""Offline regressions for full ATM10 Sky fixture and artifact provenance."""
import json
import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from atm10_sky import read_lock, safe_override, validate_url
from atm10_sky_visual import shift_command, stage_development_remapped_owo, fatal_client_launch, OWO_DEV_REMAP_NAME
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

    def test_owo_dev_remap_preserves_verified_identity_and_rejects_drift(self):
        with tempfile.TemporaryDirectory() as temp:
            base = Path(temp)
            mods = base / "client" / "mods"
            mods.mkdir(parents=True)
            evidence = base / "evidence"
            evidence.mkdir()
            jar = mods / OWO_DEV_REMAP_NAME
            payload = b"temporary regression-test mod bytes"
            jar.write_bytes(payload)
            digest = hashlib.sha256(payload).hexdigest()
            with patch("atm10_sky_visual.OWO_DEV_REMAP_SHA", digest):
                stage_development_remapped_owo(base / "client", evidence)
            self.assertFalse(jar.exists())
            remapped = json.loads((evidence / "dev-mapped-mods.json").read_text())
            self.assertEqual(remapped[0]["filename"], OWO_DEV_REMAP_NAME)
            self.assertEqual(remapped[0]["sha256"], digest)
            self.assertEqual(remapped[0]["loader"], "loom.modLocalRuntime")
            jar.write_bytes(b"corrupt")
            with patch("atm10_sky_visual.OWO_DEV_REMAP_SHA", digest):
                with self.assertRaises(ValueError):
                    stage_development_remapped_owo(base / "client", evidence)
            self.assertTrue(jar.exists())

    def test_detects_client_crash_without_false_positive_on_mod_warnings(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            launcher = root / "launcher.log"
            latest = root / "latest.log"
            launcher.write_text("[main/WARN] Mod loading: optional class absent\\n")
            latest.write_text("[main/WARN] Error loading class: optional/Dependency\\n")
            self.assertIsNone(fatal_client_launch(launcher, latest))
            launcher.write_text('Exception in thread "main" java.lang.RuntimeException: startup\\n')
            fatal = fatal_client_launch(launcher, latest)
            self.assertIn("Fatal client launcher error", fatal)
            self.assertIn("RuntimeException", fatal)
            launcher.write_text("Starting game\\n")
            latest.write_text('Uncaught exception in thread "Render thread"\\n')
            self.assertIn("Fatal Minecraft client error", fatal_client_launch(launcher, latest))

    def test_reject_malformed_fixture(self):
        for command in ("stop", "op @a", "tp @a 0 0 0"):
            with self.assertRaises(ValueError):
                shift_command(command, 48)


if __name__ == "__main__":
    unittest.main()
