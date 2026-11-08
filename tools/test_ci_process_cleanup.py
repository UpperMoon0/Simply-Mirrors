"""Offline security tests for branch-scoped JVM cleanup."""
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import ci_process_cleanup as cleanup


class CleanupTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.proc = Path(self.temp.name) / "proc"
        self.proc.mkdir()
        self.workspace = Path(self.temp.name) / "workspace" / "pr-11"
        self.workspace.mkdir(parents=True)
        self.scratch = Path(self.temp.name) / "mirrors-ci-a"
        self.scratch.mkdir()

    def add(self, pid, cmd, cwd=None, zombie=False):
        entry = self.proc / str(pid)
        entry.mkdir()
        (entry / "cmdline").write_bytes(cmd)
        (entry / "stat").write_text(str(pid) + " (java) " + ("Z" if zombie else "S") +
                                    " " + " ".join(["1"] * 18 + [str(pid * 100)]))
        if cwd:
            (entry / "cwd").symlink_to(cwd)

    def test_only_java_in_exact_workspace_or_scratch(self):
        self.add(101, b"/opt/java/bin/java\0-Dpath=" + os.fsencode(str(self.scratch)) + b"/gradle\0")
        self.add(102, b"/usr/bin/java\0-cp\0x\0", self.workspace)
        self.add(103, b"/usr/bin/java\0-Dpath=/unrelated/\0")
        self.add(104, b"/usr/bin/python3\0" + os.fsencode(str(self.workspace)) + b"/runner.py\0")
        self.add(105, b"/usr/bin/java\0/usr/share/jenkins/agent.jar\0" +
                 os.fsencode(str(self.workspace)) + b"/test\0")
        self.add(106, b"/usr/bin/java\0" + os.fsencode(str(self.workspace)) + b"/client\0",
                 zombie=True)
        found = cleanup.owned_java_processes(self.workspace, self.scratch, self.proc)
        self.assertEqual(set(found), {101, 102})

    def test_similar_prefix_is_not_owned(self):
        self.add(201, b"/usr/bin/java\0" + os.fsencode(str(self.workspace)) + b"-other/jar\0")
        self.assertEqual(cleanup.owned_java_processes(self.workspace, self.scratch, self.proc), {})

    def test_workspace_guard_requires_exact_jenskins_roots(self):
        with patch.object(cleanup, "AGENT_ROOT", Path(self.temp.name)):
            valid = {"CI_SCRATCH": str(Path(self.temp.name) / "mirrors-ci-random"),
                     "WORKSPACE": str(Path(self.temp.name) / "workspace" / "branch")}
            self.assertEqual(cleanup.scoped_paths(valid)[1].name, "mirrors-ci-random")
            with self.assertRaisesRegex(ValueError, "Unsafe"):
                cleanup.scoped_paths({**valid, "CI_SCRATCH": "/tmp/other"})
            self.assertIsNone(cleanup.scoped_paths({}))

    def test_cleaner_uses_pid_starttime_and_no_unrelated_targets(self):
        self.add(301, b"/usr/bin/java\0" + os.fsencode(str(self.scratch)) + b"/gradle\0")
        self.add(302, b"/usr/bin/java\0/usr/share/jenkins/agent.jar\0")
        env = {"CI_SCRATCH": str(self.scratch), "WORKSPACE": str(self.workspace)}
        with patch.object(cleanup, "AGENT_ROOT", Path(self.temp.name)), \
             patch.object(cleanup.os, "kill") as kill, \
             patch.object(cleanup.time, "sleep") as sleep:
            count = cleanup.cleanup_owned_processes(env, self.proc, dry_run=True)
            self.assertEqual(count, 1)
            kill.assert_not_called()


if __name__ == "__main__":
    unittest.main()
