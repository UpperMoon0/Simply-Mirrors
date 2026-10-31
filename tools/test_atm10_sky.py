"""Offline regressions for full ATM10 Sky fixture and artifact provenance."""
import json
import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from atm10_sky import read_lock, safe_override, validate_url
from atm10_sky_visual import shift_command, stage_development_remapped_owo, fatal_client_launch, OWO_DEV_REMAP_NAME
from atm10_sky_production import stage_full_pack
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

    def test_production_client_preserves_pack_and_isolates_shader_variants(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            game = base / "original"
            mods = game / "mods"
            mods.mkdir(parents=True)
            (mods / OWO_DEV_REMAP_NAME).write_bytes(b"unaltered packaged owo")
            (mods / "EuphoriaPatcher-exact.jar").write_bytes(b"unaltered euphoria")
            (game / "shaderpacks").mkdir()
            (game / "shaderpacks" / "ComplementaryReimagined_r5.9.3.zip").write_bytes(b"shader")
            world = base / "fixture"
            world.mkdir()
            (world / "level.dat").write_bytes(b"disposable world")
            qa = base / "qa.jar"
            qa.write_bytes(b"production QA instrumentation")
            evidence = base / "evidence"
            evidence.mkdir()
            with patch("atm10_sky_production.MODULE", base / "module"):
                off = stage_full_pack(game, world, "off", "off", qa, evidence)
                complementary = stage_full_pack(
                    game, world, "complementary", "complementary", qa, evidence)
            self.assertEqual((off / "mods" / OWO_DEV_REMAP_NAME).read_bytes(),
                             (mods / OWO_DEV_REMAP_NAME).read_bytes())
            self.assertTrue((off / "mods" / "EuphoriaPatcher-exact.jar").exists())
            self.assertFalse((complementary / "mods" / "EuphoriaPatcher-exact.jar").exists())
            self.assertTrue((mods / "EuphoriaPatcher-exact.jar").exists())
            self.assertTrue((off / "mods" / "qa.jar").exists())
            self.assertTrue((complementary / "saves" / "atm10-sky-mirror-fixture" / "level.dat").exists())

    def test_atm10_probe_helper_is_outside_sponge_mixin_package(self):
        # Sponge Mixin disallows loading *any* ordinary class from a mixin
        # package, including helper classes that are not listed as mixins.
        helper = ROOT / "visual-tests/1.21.1/java/com/nstut/simplymirrors/visualsupport/Atm10SkyProbe.java"
        mixins = ROOT / "visual-tests/1.21.1/java/com/nstut/simplymirrors/visualtest"
        self.assertTrue(helper.is_file())
        self.assertTrue(helper.read_text().startswith(
            "package com.nstut.simplymirrors.visualsupport;"))
        self.assertFalse((mixins / "Atm10SkyProbe.java").exists())
        probe = (mixins / "VisualProbeMixin.java").read_text()
        self.assertIn("import com.nstut.simplymirrors.visualsupport.Atm10SkyProbe;", probe)
        jar_source = (ROOT / "tools/atm10_sky_production.py").read_text()
        self.assertIn("com/nstut/simplymirrors/visualsupport/Atm10SkyProbe.class", jar_source)
        self.assertNotIn("com/nstut/simplymirrors/visualtest/Atm10SkyProbe.class", jar_source)

    def test_drippy_boot_guard_only_in_temporary_visual_fixture(self):
        import os
        import re
        root = Path(__file__).resolve().parents[1]
        path = root / "visual-tests/1.21.1/java/com/nstut/simplymirrors/visualtest/DrippyEarlyBootMixin.java"
        source = path.read_text()
        self.assertIn('Boolean.getBoolean("simplymirrors.atm10Sky")', source)
        self.assertIn('registry.get(null) == null', source)
        self.assertIn('ci.cancel()', source)
        config = json.loads((root / "visual-tests/resources/simplymirrors.visual.mixins.json").read_text())
        self.assertNotIn("DrippyEarlyBootMixin", config["client"])
        gradle = (root / "tools/visual.gradle").read_text()
        self.assertIn("parsed.client.add('DrippyEarlyBootMixin')", gradle)
        self.assertIn("providers.gradleProperty('atm10Sky').isPresent()", gradle)
        qa = (root / "tools/atm10_sky_production.py").read_text()
        self.assertIn('gradle("-PvisualPort=0", "-Patm10QaJar",', qa)
        self.assertIn("providers.gradleProperty('atm10QaJar').isPresent()", gradle)
        # The production source sets must not contain the test bootstrap patch.
        self.assertFalse(any(root.glob("common*/src/main/**/DrippyEarlyBootMixin.java")))

    def test_reject_malformed_fixture(self):
        for command in ("stop", "op @a", "tp @a 0 0 0"):
            with self.assertRaises(ValueError):
                shift_command(command, 48)


if __name__ == "__main__":
    unittest.main()
