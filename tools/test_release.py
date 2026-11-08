"""Release regression tests: no network calls or uploads."""
import os
import json
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



    def test_source_checkout_cannot_publish_or_preflight(self):
        import subprocess, sys
        for command in ('publish','preflight','plan'):
            result=subprocess.run([sys.executable,str(release.ROOT/'tools/release.py'),command],capture_output=True,text=True)
            self.assertNotEqual(result.returncode,0)
            self.assertIn('NsTut-CICD',result.stderr)

    def test_release_provenance_is_bound_to_source_and_hashed(self):
        import hashlib
        from jenkins_collect import write_provenance
        with tempfile.TemporaryDirectory() as directory:
            output=Path(directory)
            (output/'SHA256SUMS').write_text('')
            write_provenance(output,'a'*40,'0.1.3',release.TARGETS)
            data=json.loads((output/'provenance.json').read_text())
            self.assertEqual(data['source_sha'],'a'*40)
            self.assertEqual(data['targets'],sorted(release.TARGETS))
            self.assertEqual((output/'SHA256SUMS').read_text(),hashlib.sha256((output/'provenance.json').read_bytes()).hexdigest()+'  provenance.json\n')
            with self.assertRaises(ValueError):
                write_provenance(output,'main','0.1.3',release.TARGETS)

if __name__ == "__main__": unittest.main()
