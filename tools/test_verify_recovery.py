"""Recovery requires the exact current acceptance matrix and original published bytes."""
import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import verify_recovery as recovery


def successful_jobs():
    names = ["build / Reflection math (Java 8)", "build / Shader history isolation (OpenGL)",
             "build / Verify eight-target release bundle"]
    names += [f"build / {target}" for target in recovery.TARGETS]
    names += [f"visual / Visual {target}" for target in recovery.TARGETS]
    names += [f"shaders / Complementary {target}" for target in recovery.TARGETS]
    return [{"name": name, "conclusion": "success"} for name in names]


class RecoveryTests(unittest.TestCase):
    def test_exact_matrix_passes_even_when_publishing_failed(self):
        jobs = successful_jobs()
        self.assertEqual(27, len(jobs))
        recovery.verify_jobs(jobs + [{"name": "release", "conclusion": "failure"}])

    def test_failed_skipped_missing_duplicate_or_wrong_jobs_fail(self):
        for name in ["build / Shader history isolation (OpenGL)",
                     "shaders / Complementary forge-1.16.5", "visual / Visual fabric-1.21.1",
                     "build / neoforge-26.1.2"]:
            for conclusion in ["failure", "skipped", "cancelled", None]:
                jobs = successful_jobs()
                next(job for job in jobs if job["name"] == name)["conclusion"] = conclusion
                with self.subTest(name=name, conclusion=conclusion), self.assertRaises(ValueError):
                    recovery.verify_jobs(jobs)
        jobs = successful_jobs()
        for invalid in [jobs[:-1], jobs+[jobs[0]], jobs[:-1]+[jobs[0]],
                        jobs[:-1]+[{"name": "shaders / Unrelated", "conclusion": "success"}]]:
            with self.assertRaises(ValueError): recovery.verify_jobs(invalid)

    def test_original_commit_and_all_published_digests_still_required(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            names = [recovery.expected_name(t, recovery.version()) for t in recovery.TARGETS]+["SHA256SUMS"]
            assets = []
            for name in names:
                payload = name.encode()
                (directory/name).write_bytes(payload)
                assets.append({"name": name, "digest": "sha256:"+hashlib.sha256(payload).hexdigest()})
            run = {"headSha": "a"*40, "headBranch": "main", "jobs": successful_jobs()}
            published = {"targetCommitish": "a"*40, "isPrerelease": False, "assets": assets}
            with patch("sys.argv", ["verify_recovery.py", "123", str(directory)]):
                with patch.object(recovery, "gh", side_effect=[run, published]): recovery.main()
                for mutation in [{"targetCommitish": "b"*40}, {"isPrerelease": True}, {"assets": assets[:-1]}]:
                    with patch.object(recovery, "gh", side_effect=[run, dict(published, **mutation)]):
                        with self.assertRaises(ValueError): recovery.main()
                with patch.object(recovery, "gh", side_effect=[dict(run, headBranch="feature"), published]):
                    with self.assertRaises(ValueError): recovery.main()
                (directory/names[0]).write_bytes(b"replacement")
                with patch.object(recovery, "gh", side_effect=[run, published]):
                    with self.assertRaises(ValueError): recovery.main()


if __name__ == "__main__":
    unittest.main()
