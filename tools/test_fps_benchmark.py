"""Offline regression coverage for Windows real-GPU benchmark teardown.

No Minecraft, GPU, network or live processes are used in these tests.
"""
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch, call

import fps_benchmark


class NativeGraphicsSecurityTests(unittest.TestCase):
    def test_benchmark_never_uses_user_session_token_broker(self):
        text=(Path(__file__).parent / "fps_benchmark.py").read_text(encoding="utf-8")
        self.assertIn("start_private_graphics_client(", text)
        self.assertIn('os.environ.get("CI_GRAPHICS_AGENT") != "1"', text)
        self.assertIn("subprocess.Popen(command", text)
        for unsafe in ("WTSQueryUserToken(", "CreateProcessAsUserW(", 'winsta0\\\\default',
                       "def new_interactive_client("):
            self.assertNotIn(unsafe, text)

    def test_benchmark_fails_closed_without_native_graphics_agent(self):
        from unittest.mock import patch
        with patch.dict(fps_benchmark.os.environ, {"CI_GRAPHICS_AGENT": "0"}):
            with self.assertRaisesRegex(RuntimeError, "reviewed native Windows graphics agent"):
                fps_benchmark.start_private_graphics_client(
                    ["gradlew"], Path.cwd(), Path("fake.log"), "fake-java")



class GraphicsOptionsTests(unittest.TestCase):
    def test_default_never_enables_unproven_frustum_crop(self):
        options = fps_benchmark.graphics_java_options()
        self.assertIn("-Dsimplymirrors.fpsBenchmark=true", options)
        self.assertNotIn("frustumCrop=true", options)
        self.assertNotIn("coverageScissor=false", options)

    def test_explicit_culling_and_control_are_independent(self):
        options = fps_benchmark.graphics_java_options(
            gpu_profile=True, coverage_scissor=False, frustum_crop=True)
        self.assertIn("-Dsimplymirrors.gpuProfile=true", options)
        self.assertIn("-Dsimplymirrors.coverageScissor=false", options)
        self.assertIn("-Dsimplymirrors.frustumCrop=true", options)

    def test_regular_control_has_no_experimental_frustum_crop(self):
        options = fps_benchmark.graphics_java_options(coverage_scissor=False)
        self.assertNotIn("frustumCrop=true", options)



class FramePassCadenceTests(unittest.TestCase):
    def evidence(self, scene, passes):
        return {"scenario": scene, "samples": len(passes),
                "frameIntervalsNs": [16_000_000] * len(passes),
                "passesPerFrame": passes, "reflectionPasses": sum(passes)}

    def test_baseline_requires_zero_per_frame(self):
        fps_benchmark.validate_frame_pass_cadence(self.evidence(7, [0] * 40))
        with self.assertRaisesRegex(AssertionError, "frame 19"):
            fps_benchmark.validate_frame_pass_cadence(
                self.evidence(7, [0] * 19 + [1] + [0] * 20))

    def test_each_mirror_frame_requires_one_pass(self):
        for scene in (8, 9, 18, 29):
            fps_benchmark.validate_frame_pass_cadence(self.evidence(scene, [1] * 40))
            for corrupt in ([1] * 17 + [0] + [1] * 22,
                            [1] * 17 + [2] + [1] * 22):
                with self.assertRaisesRegex(AssertionError, "frame 17"):
                    fps_benchmark.validate_frame_pass_cadence(self.evidence(scene, corrupt))

    def test_bad_samples_and_aggregate_cannot_pass(self):
        data = self.evidence(8, [1] * 40)
        data["samples"] = 39
        with self.assertRaisesRegex(AssertionError, "Malformed"):
            fps_benchmark.validate_frame_pass_cadence(data)
        data = self.evidence(8, [1] * 40)
        data["reflectionPasses"] += 1
        with self.assertRaisesRegex(AssertionError, "total disagrees"):
            fps_benchmark.validate_frame_pass_cadence(data)


class OwnedGradleWrapperCleanupTests(unittest.TestCase):
    @patch.object(fps_benchmark.subprocess, "run")
    def test_lookup_scopes_exact_run_directory_name_and_gradle_wrapper(self, run):
        run.return_value = SimpleNamespace(returncode=0, stdout="4132\n5801\n",
                                           stderr="")
        root = Path(r"D:\Workspaces\_work\simply-mirrors-real-gpu-fps-20261105")
        found = fps_benchmark.owned_gradle_server_wrapper_pids(root, "fps-unique-03")
        self.assertEqual([4132, 5801], found)
        args = run.call_args.args[0]
        self.assertEqual("powershell.exe", args[0].lower())
        powershell = args[-1]
        self.assertIn("GradleWrapperMain", powershell)
        self.assertIn("-PvisualRunName=fps-unique-03", powershell)
        self.assertIn("$_." + "Name -eq 'java.exe'", powershell)
        self.assertIn("simply-mirrors-real-gpu-fps-20261105", powershell)

    @patch("fps_benchmark.stop_tree")
    @patch("fps_benchmark.owned_gradle_server_wrapper_pids",
           side_effect=[[4132, 5801], []])
    def test_cleanup_kills_only_scoped_wrappers_and_rechecks(self, lookup, terminate):
        fps_benchmark.cleanup_owned_gradle_servers(Path("D:/isolated"), "fps-a")
        self.assertEqual([call(4132), call(5801)], terminate.call_args_list)
        self.assertEqual(2, lookup.call_count)

    @patch("fps_benchmark.stop_tree")
    @patch("fps_benchmark.owned_gradle_server_wrapper_pids",
           side_effect=[[4132], [4132]])
    def test_cleanup_fails_when_owned_wrapper_remains(self, lookup, terminate):
        with self.assertRaisesRegex(RuntimeError, "server wrappers leaked"):
            fps_benchmark.cleanup_owned_gradle_servers(Path("D:/isolated"), "fps-a")
        terminate.assert_called_once_with(4132)

    @patch.object(fps_benchmark.subprocess, "run")
    def test_process_enumeration_failure_does_not_claim_clean_exit(self, run):
        run.return_value = SimpleNamespace(returncode=1, stdout="",
                                           stderr="WMI not available")
        with self.assertRaisesRegex(RuntimeError, "Failed to enumerate"):
            fps_benchmark.owned_gradle_server_wrapper_pids(
                Path("D:/isolated"), "fps-a")


if __name__ == "__main__":
    unittest.main()
