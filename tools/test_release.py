"""Release regression tests: no network calls or uploads."""
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import release

class ReleaseTests(unittest.TestCase):
    def test_version_accepts_crlf_and_rejects_duplicate_or_unsafe_values(self):
        self.assertEqual("1.2.3-beta.1", release.version("mod_version = 1.2.3-beta.1\r\n"))
        for text in ["mod_version=1.0.0\nmod_version=2.0.0", "mod_version=../bad", "mod_version=1.0.0\nBAD=1\nmod_version=2.0.0"]:
            with self.assertRaises(ValueError): release.version(text)

    def test_manual_dry_run_and_non_main_never_publish(self):
        env = {"GITHUB_REF": "refs/heads/main", "EVENT_NAME": "workflow_dispatch"}
        self.assertEqual("false", release.plan(env)["publish"])
        env["REQUEST_PUBLISH"] = "true"
        self.assertEqual("true", release.plan(env)["publish"])
        env["GITHUB_REF"] = "refs/heads/codex/test"
        self.assertEqual("false", release.plan(env)["publish"])

    @patch("release.version", return_value="1.2.3")
    @patch("release.subprocess.check_output", return_value="mod_version=1.2.3\r\n")
    def test_unchanged_version_does_not_republish(self, git, version):
        version.side_effect = lambda text=None: "1.2.3" if text is None else text.split("=", 1)[1].strip()
        env = {"GITHUB_REF": "refs/heads/main", "EVENT_NAME": "push", "BEFORE_SHA": "a" * 40}
        self.assertEqual("false", release.plan(env)["publish"])
        git.return_value = "mod_version=1.2.2\n"
        self.assertEqual("true", release.plan(env)["publish"])

    def test_release_types_and_correct_loader_dependencies(self):
        for v, kind in [("1.0.0", "release"), ("1.0.0-dev.4", "alpha"), ("1.0.0-beta.1", "beta")]:
            self.assertEqual(kind, release.release_type(v))
        for info in release.TARGETS.values():
            meta = release.metadata(info, release.version())
            relations = meta["relations"]["projects"]
            self.assertEqual(info["loader"] == "fabric", bool(relations))
            self.assertIn(info["minecraft"], meta["gameVersionNames"])
            self.assertIn(f"Java {info['java']}", meta["gameVersionNames"])

    @patch("release.verify")
    def test_collect_checks_all_targets_before_creating_output(self, verify):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); source = root / "input"; source.mkdir(); output = root / "out"
            for target in release.TARGETS:
                (source / release.expected_name(target, release.version())).write_bytes(b"fixture")
            verify.side_effect = ValueError("Stale metadata")
            with self.assertRaises(ValueError): release.collect(source, output)
            self.assertFalse(output.exists())
            verify.side_effect = None; verify.reset_mock()
            release.collect(source, output)
            self.assertEqual(8, verify.call_count)
            self.assertEqual(8, len((output / "SHA256SUMS").read_text().splitlines()))
            (source / "extra-dev.jar").write_bytes(b"fixture")
            with self.assertRaises(ValueError): release.collect(source, root / "out2")

    def test_missing_credentials_fail_before_network(self):
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaisesRegex(ValueError, "CURSEFORGE_PROJECT_ID"):
                release.settings()
        with patch.dict(os.environ, {"CURSEFORGE_PROJECT_ID": "123"}, clear=True):
            with self.assertRaisesRegex(ValueError, "CURSEFORGE_API_TOKEN"):
                release.settings()

if __name__ == "__main__": unittest.main()
