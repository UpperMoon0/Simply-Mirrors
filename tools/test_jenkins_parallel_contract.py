"""The mod source delegates CI execution to the reviewed central library."""
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]

class CentralizedMirrorPipelineTests(unittest.TestCase):
    def test_entrypoint_delegates_all_execution(self):
        self.assertEqual((ROOT / 'Jenkinsfile').read_text().strip(),
                         "@Library('nstut-ci') _\nnstutMinecraftMod(project: 'simply-mirrors')")
        body = (ROOT / 'Jenkinsfile').read_text()
        for linux_assumption in ('linux-minecraft-ci', 'xvfb-run', 'mktemp', 'gradlew'):
            self.assertNotIn(linux_assumption, body)

    def test_target_matrix_retained_in_mod_source(self):
        matrix = json.loads((ROOT / 'tools/targets.json').read_text())
        self.assertEqual([x['target'] for x in matrix],
            ['fabric-1.16.5','forge-1.16.5','fabric-1.20.1','forge-1.20.1',
             'fabric-1.21.1','neoforge-1.21.1','fabric-26.1.2','neoforge-26.1.2'])

    def test_shader_regression_is_os_neutral(self):
        script = (ROOT / 'tools/jenkins_shader_regressions.py').read_text()
        self.assertIn("if os.name == 'nt':", script)
        self.assertIn("command = ['xvfb-run'", script)
        self.assertIn("command.append('--interactive-offscreen')", script)
        self.assertIn("CI_MINECRAFT_JOB_ID", script)
        self.assertNotIn('from ci_process_cleanup import', script)

if __name__ == '__main__':
    unittest.main()
