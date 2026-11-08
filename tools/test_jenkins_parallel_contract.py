"""Executable CI contracts: expandable per-target Jenkins dispatch and cache isolation."""
import json
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
JENKINS = (ROOT / "Jenkinsfile").read_text()
TARGETS = json.loads((ROOT / "tools/targets.json").read_text())


class ParallelJenkinsContracts(unittest.TestCase):
    def test_exact_supported_matrix_is_dispatched(self):
        found = re.search(r"def targets\(\)\s*\{(.*?)\}", JENKINS, re.S)
        self.assertIsNotNone(found)
        configured = re.findall(r"'((?:fabric|forge|neoforge)-[\d.]+)'", found.group(1))
        self.assertEqual(configured, [entry["target"] for entry in TARGETS])
        self.assertEqual(len(configured), 8)
        self.assertIn('for (String name : targets())', JENKINS)
        self.assertIn('parallel jobs', JENKINS)

    def test_coordinator_does_not_hold_a_worker(self):
        self.assertRegex(JENKINS, r"pipeline\s*\{\s*agent none")
        self.assertIn("node('linux-minecraft-ci')", JENKINS)
        self.assertIn("jobs[target] = {", JENKINS)
        self.assertIn("withMirrorWorker(target)", JENKINS)
        self.assertIn("disableConcurrentBuilds(abortPrevious: true)", JENKINS)
        self.assertNotIn("pipeline {\n    agent { label 'linux-minecraft-ci' }", JENKINS)

    def test_worker_caches_only_dependencies_not_workspaces(self):
        self.assertIn('GRADLE_USER_HOME=/home/jenkins/.gradle', JENKINS)
        self.assertIn('PIP_CACHE_DIR=/home/jenkins/agent/caches/pip', JENKINS)
        self.assertIn("flock -x 9", JENKINS)
        self.assertIn("mktemp -d /home/jenkins/agent/mirrors-ci-XXXXXX", JENKINS)
        self.assertIn("checkout scm", JENKINS)
        self.assertIn("cleanWs(deleteDirs: true, disableDeferredWipeout: true)", JENKINS)
        self.assertIn("python3 tools/ci_process_cleanup.py", JENKINS)
        self.assertNotIn('GRADLE_USER_HOME=' + '$' + 'CI_SCRATCH', JENKINS)

    def test_coverage_and_release_gates_are_retained(self):
        for step in ("python tools/build.py", "tools/visual_smoke.py",
                     "tools/jenkins_shader_regressions.py",
                     ":common:shaderHistoryGlTest", ":common:mirrorCompositeBenchmark"):
            self.assertIn(step, JENKINS)
        self.assertIn("if (target == 'neoforge-1.21.1')", JENKINS)
        self.assertIn("when { branch 'main' }", JENKINS)
        self.assertIn("stash name: 'release-' + target", JENKINS)
        self.assertIn("unstash 'release-' + target", JENKINS)
        self.assertIn("python tools/jenkins_collect.py", JENKINS)

    def test_one_failed_target_does_not_skip_others(self):
        self.assertIn("catchError(buildResult: 'FAILURE', stageResult: 'FAILURE')", JENKINS)
        self.assertIn("timeout(time: 40, unit: 'MINUTES')", JENKINS)
        self.assertIn("timeout(time: 45, unit: 'MINUTES')", JENKINS)
        self.assertIn("timeout(time: 120, unit: 'MINUTES')", JENKINS)
        self.assertLess(JENKINS.index("stage('Build ' + target)"),
                        JENKINS.index("stage('Visual ' + target)"))
        self.assertLess(JENKINS.index("stage('Visual ' + target)"),
                        JENKINS.index("stage('Shaders ' + target)"))


if __name__ == "__main__":
    unittest.main()
