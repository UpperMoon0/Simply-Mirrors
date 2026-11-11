import json
from pathlib import Path
"""Pure contract tests: no GPU, server, or user client required."""
from unittest import TestCase
from unittest.mock import patch
import aperture_ci
import fps_benchmark

class ApertureNativeAcceptanceTests(TestCase):
    def test_only_trial_enables_aperture(self):
        control = aperture_ci.benchmark_command("control", "control")
        trial = aperture_ci.benchmark_command("trial", "trial")
        for args in (control, trial):
            self.assertIn("--shader-tail-crop", args)
            self.assertIn("--history-copy-image", args)
            self.assertIn("--moving-camera", args)
            self.assertNotIn("--shader-final-dual-crop", args)
            self.assertEqual("1920", args[args.index("--width") + 1])
            self.assertEqual("1080", args[args.index("--height") + 1])
            self.assertEqual("12", args[args.index("--render-distance") + 1])
        self.assertNotIn("--aperture-cull", control)
        self.assertIn("--aperture-cull", trial)

    def test_native_java_options_default_off_explicit_on(self):
        control = fps_benchmark.graphics_java_options()
        trial = fps_benchmark.graphics_java_options(aperture_cull=True)
        self.assertNotIn("simplymirrors.apertureCull=true", control)
        self.assertIn("-Dsimplymirrors.apertureCull=true", trial)

    def test_summary_must_certify_flag_reaches_client(self):
        baseline = {
            "shaderTailCropEnabled": True,
            "historyCopyImageEnabled": True,
            "shaderFinalOriginCropEnabled": False,
            "shaderEarlyCompositeCropEnabled": False,
            "shaderFinalDualCropEnabled": False,
            "frustumCropEnabled": False,
            "apertureCullEnabled": False,
            "resolution": "1920x1080",
            "renderDistance": 12,
            "movingCameraEnabled": True,
        }
        aperture_ci.verify_settings(baseline, "control")
        with self.assertRaisesRegex(AssertionError, "apertureCullEnabled"):
            aperture_ci.verify_settings(baseline, "trial")
        baseline["apertureCullEnabled"] = True
        aperture_ci.verify_settings(baseline, "trial")
        with self.assertRaisesRegex(AssertionError, "apertureCullEnabled"):
            aperture_ci.verify_settings(baseline, "control")
        baseline["shaderFinalDualCropEnabled"] = True
        with self.assertRaisesRegex(AssertionError, "shaderFinalDualCropEnabled"):
            aperture_ci.verify_settings(baseline, "trial")

    def test_must_reject_no_op_trials(self):
        with self.assertRaisesRegex(AssertionError, "lacks proof"):
            aperture_ci.aperture_telemetry("unrelated output")
        with self.assertRaisesRegex(AssertionError, "lacks proof"):
            aperture_ci.aperture_telemetry("SIMPLY_MIRRORS_APERTURE_CULL sectionsChecked=500 sectionsCulled=0")
        with self.assertRaisesRegex(AssertionError, "Embeddium terrain"):
            aperture_ci.aperture_telemetry(
                "SIMPLY_MIRRORS_APERTURE_CULL sectionsChecked=500 sectionsCulled=150")
        self.assertEqual({"sectionsChecked": 600, "sectionsCulled": 140,
                          "sodiumSectionsChecked": 500, "sodiumSectionsCulled": 125},
                         aperture_ci.aperture_telemetry(
                             "SIMPLY_MIRRORS_APERTURE_CULL sectionsChecked=400 sectionsCulled=100 sodiumSectionsChecked=350 sodiumSectionsCulled=90\n"
                             "SIMPLY_MIRRORS_APERTURE_CULL sectionsChecked=200 sectionsCulled=40 sodiumSectionsChecked=150 sodiumSectionsCulled=35"))

    def test_untrusted_agent_cannot_start_acceptance(self):
        with patch.dict(aperture_ci.os.environ, {"CI_GRAPHICS_AGENT": "0"}):
            with self.assertRaisesRegex(RuntimeError, "trusted Windows GPU"):
                aperture_ci.accept()

class ApertureMixinRegistrationTests(TestCase):
    def test_embeddium_hook_in_primary_1_20_1_config(self):
        root = Path(__file__).resolve().parents[1]
        mixins = json.loads((root / "common-1.20.1/src/main/resources/simplymirrors.mixins.json")
                            .read_text(encoding="utf-8"))
        self.assertEqual("com.nstut.simplymirrors.client.mixin.EmbeddiumAperturePlugin",
                         mixins["plugin"])
        self.assertEqual(1, mixins["client"].count("EmbeddiumFrustumMixin"))
        self.assertIn("MirrorFrustumMixin", mixins["client"])
        self.assertTrue(mixins["required"])

    def test_no_secondary_config_and_primary_plugin_preserves_required_mixins(self):
        root = Path(__file__).resolve().parents[1]
        forge = (root / "forge-1.20.1/build.gradle").read_text(encoding="utf-8")
        self.assertNotIn("simplymirrors.embeddium.mixins.json", forge)
        self.assertEqual(2, forge.count("simplymirrors.mixins.json"))
        self.assertFalse((root / "forge-1.20.1/src/main/resources/simplymirrors.embeddium.mixins.json").exists())
        plugin = (root / "common-1.20.1/src/main/java/com/nstut/simplymirrors/client/mixin/EmbeddiumAperturePlugin.java").read_text(encoding="utf-8")
        self.assertIn('if (!mixinClassName.endsWith(".EmbeddiumFrustumMixin")) return true;', plugin)
