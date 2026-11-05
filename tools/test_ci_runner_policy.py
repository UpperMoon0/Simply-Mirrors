"""Prevent privileged/private-runner regressions in the public mod's CI."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
WORKFLOWS = ROOT / ".github" / "workflows"
POOL = "runs-on: [self-hosted, linux, x64, nstut-linux-pool, nstut-profile-minecraft-ci]"
TRUST = "github.event_name != 'pull_request' || github.event.pull_request.head.repo.full_name == github.repository"


def jobs(path: Path):
    content = path.read_text(encoding="utf-8")
    assert "\njobs:\n" in content, f"missing workflow jobs: {path}"
    parts = re.split(r"(?m)^  ([a-z][a-z0-9-]+):\s*$", content.split("\njobs:\n", 1)[1])
    return content, dict(zip(parts[1::2], parts[2::2]))


class PrivateFleetCiTests(unittest.TestCase):
    def test_every_executable_job_uses_socketless_portable_linux_pool(self):
        for workflow in ("build.yml", "visual.yml", "shaders.yml", "release.yml"):
            with self.subTest(workflow=workflow):
                source, definitions = jobs(WORKFLOWS / workflow)
                self.assertNotIn("runs-on: ubuntu-", source)
                self.assertNotIn("sudo apt-get", source)
                self.assertNotIn("pull_request_target:", source)
                runners = 0
                for name, body in definitions.items():
                    if "runs-on:" not in body:
                        self.assertIn("uses: ./.github/workflows/", body)
                        continue
                    runners += 1
                    self.assertIn(POOL, body, f"{workflow} job {name}")
                    if workflow != "release.yml":
                        self.assertIn(f"if: {TRUST}", body, f"Fork PR code must not run on {name}")
                self.assertGreater(runners, 0)

    def test_fleet_image_provides_visual_dependencies_without_privileged_install(self):
        for workflow in ("build.yml", "visual.yml", "shaders.yml"):
            source, _ = jobs(WORKFLOWS / workflow)
            self.assertIn("command -v Xvfb", source) if workflow == "visual.yml" or workflow == "shaders.yml" or workflow == "build.yml" else None
        release, _ = jobs(WORKFLOWS / "release.yml")
        self.assertIn("github.event_name == 'workflow_dispatch' && inputs.publish", release)
        self.assertIn("persist-credentials: false", release)


if __name__ == "__main__":
    unittest.main()
