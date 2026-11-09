"""Regression checks for server-authorized visual scene transitions."""
from pathlib import Path
import tempfile
import os
import subprocess
import json
import unittest
from unittest.mock import Mock, patch
import visual_smoke

class EnclosedRoomFixtureTests(unittest.TestCase):
    def test_enclosed_scene_preserves_markers_and_seals_every_face(self):
        for scene in range(6):
            with self.subTest(scene=scene):
                plain = visual_smoke.fixture(scene)
                indoor = visual_smoke.fixture(scene, enclosed=True)
                self.assertEqual(9, len(indoor) - len(plain))
                self.assertEqual(plain[:5], indoor[:5])
                self.assertEqual(plain[-9:], indoor[-9:])
                self.assertTrue(all(cmd.startswith("fill ") for cmd in indoor[5:14]))
                self.assertTrue(indoor[10].endswith(" white_concrete"))
                # Enclosed, opaque rooms need an internal light source. Otherwise
                # shader exposure produces near-black but still-correct geometry.
                self.assertEqual(3, len(indoor[11:14]))
                self.assertTrue(all(cmd.endswith(" sea_lantern") for cmd in indoor[11:14]))
                self.assertEqual(3, len(set(indoor[11:14])))
                self.assertTrue(all(cmd.startswith("setblock ") for cmd in indoor[-9:]))



class VisualLoginRecoveryTests(unittest.TestCase):
    def test_disconnect_signal_is_scoped_to_current_client_launch(self):
        with tempfile.TemporaryDirectory() as temp:
            log=Path(temp)/"client.log"
            log.write_text(
                "SIMPLY_MIRRORS_VISUAL_STAGE net.minecraft.client.gui.screens.DisconnectedScreen\\n")
            offset=log.stat().st_size
            self.assertTrue(visual_smoke.disconnected_during_login(log,0))
            self.assertFalse(visual_smoke.disconnected_during_login(log,offset))
            with log.open("a") as stream:
                stream.write("SIMPLY_MIRRORS_VISUAL_STAGE net.minecraft.client.gui.screens.ConnectScreen\\n")
            self.assertFalse(visual_smoke.disconnected_during_login(log,offset))
            with log.open("a") as stream:
                stream.write("SIMPLY_MIRRORS_VISUAL_STAGE net.minecraft.client.gui.screens.DisconnectedScreen\\n")
            self.assertTrue(visual_smoke.disconnected_during_login(log,offset))

    def test_explicit_disconnect_is_raised_before_ten_minute_timeout(self):
        with tempfile.TemporaryDirectory() as temp:
            directory=Path(temp)
            log=directory/"client.log"
            log.write_text("SIMPLY_MIRRORS_VISUAL_STAGE net.minecraft.client.gui.screens.DisconnectedScreen\\n")
            rcon=Mock()
            rcon.command.return_value="No player was found"
            child=Mock()
            child.poll.return_value=None
            with self.assertRaisesRegex(visual_smoke.VisualClientDisconnected,"disconnected"):
                visual_smoke.activate_scene(rcon,directory,0,child,
                                            client_log_path=log,log_offset=0)
            rcon.command.assert_called_once()

    def test_one_login_retry_still_requires_server_authorized_teleport(self):
        with tempfile.TemporaryDirectory() as temp:
            directory=Path(temp)
            evidence=directory/"evidence"
            evidence.mkdir()
            client_dir=directory/"client"
            client_dir.mkdir()
            log_path=evidence/"client.log"
            first=Mock(pid=101)
            second=Mock(pid=102)
            with log_path.open("ab") as stream, \
                 patch.object(visual_smoke.subprocess,"Popen",side_effect=[first,second]) as spawn, \
                 patch.object(visual_smoke,"activate_scene",
                              side_effect=[visual_smoke.VisualClientDisconnected("temporary login failure"),None]) as activate, \
                 patch.object(visual_smoke,"stop") as stop:
                result=visual_smoke.launch_initial_visual_client(
                    Mock(),client_dir,["gradlew","runVisualClient"],directory,stream,
                    {},{},evidence,log_path)
            self.assertIs(result,second)
            self.assertEqual(2,spawn.call_count)
            self.assertEqual(2,activate.call_count)
            stop.assert_called_once_with(first)
            self.assertEqual("-1",(client_dir/"visual-scene.txt").read_text())
            self.assertTrue((evidence/"login-disconnect-attempt-1.log").exists())

    def test_two_explicit_disconnects_fail_closed_no_third_launch(self):
        with tempfile.TemporaryDirectory() as temp:
            directory=Path(temp)
            evidence=directory/"evidence"
            evidence.mkdir()
            client_dir=directory/"client"
            client_dir.mkdir()
            log_path=evidence/"client.log"
            with log_path.open("ab") as stream, \
                 patch.object(visual_smoke.subprocess,"Popen",
                              side_effect=[Mock(pid=101),Mock(pid=102)]) as spawn, \
                 patch.object(visual_smoke,"activate_scene",
                              side_effect=visual_smoke.VisualClientDisconnected("disconnected")), \
                 patch.object(visual_smoke,"stop") as stop:
                with self.assertRaises(visual_smoke.VisualClientDisconnected):
                    visual_smoke.launch_initial_visual_client(
                        Mock(),client_dir,["gradlew","runVisualClient"],directory,
                        stream,{}, {},evidence,log_path)
            self.assertEqual(2,spawn.call_count)
            self.assertEqual(2,stop.call_count)
            self.assertTrue((evidence/"login-disconnect-attempt-2.log").exists())

    def test_arbitrary_client_failure_does_not_trigger_retry(self):
        with tempfile.TemporaryDirectory() as temp:
            directory=Path(temp)
            evidence=directory/"evidence"
            evidence.mkdir()
            client_dir=directory/"client"
            client_dir.mkdir()
            log_path=evidence/"client.log"
            with log_path.open("ab") as stream, \
                 patch.object(visual_smoke.subprocess,"Popen",return_value=Mock(pid=101)) as spawn, \
                 patch.object(visual_smoke,"activate_scene",side_effect=RuntimeError("render failure")), \
                 patch.object(visual_smoke,"stop") as stop:
                with self.assertRaisesRegex(RuntimeError,"render failure"):
                    visual_smoke.launch_initial_visual_client(
                        Mock(),client_dir,["gradlew","runVisualClient"],directory,
                        stream,{}, {},evidence,log_path)
            spawn.assert_called_once()
            stop.assert_called_once()


class WindowsSceneFileHandoffTests(unittest.TestCase):
    @patch("visual_smoke.time.sleep")
    def test_transient_reader_lock_retries_without_destroying_scene(self, sleeper):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            visual_smoke.scene_file(directory, 0)
            original = Path.replace
            calls = []

            def replace(path, destination):
                calls.append((path, destination))
                if len(calls) == 1:
                    self.assertEqual("0", (directory / "visual-scene.txt").read_text())
                    raise PermissionError(13, "Windows reader sharing violation")
                return original(path, destination)

            with patch.object(Path, "replace", replace):
                visual_smoke.scene_file(directory, 6)
            self.assertEqual(2, len(calls))
            self.assertEqual("6", (directory / "visual-scene.txt").read_text())
            self.assertFalse((directory / "visual-scene.next").exists())
            sleeper.assert_called_once_with(.05)

    @patch("visual_smoke.time.sleep")
    def test_persistent_permission_error_remains_a_failure(self, sleeper):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            visual_smoke.scene_file(directory, 0)
            with patch.object(Path, "replace", side_effect=PermissionError(13, "Denied")):
                with self.assertRaisesRegex(PermissionError, "Denied"):
                    visual_smoke.scene_file(directory, 6)
            self.assertEqual("0", (directory / "visual-scene.txt").read_text())
            self.assertEqual(11, sleeper.call_count)


class VisualServerPreparationTests(unittest.TestCase):
    @patch("visual_smoke.subprocess.run")
    def test_compile_preflight_is_a_separate_bounded_phase(self, runner):
        runner.return_value = Mock(returncode=0)
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            visual_smoke.prepare_visual_server_build(
                ["./gradlew", "-PvisualTest"], ":neoforge-1.21.1:classes",
                directory, directory)
            command = runner.call_args.args[0]
            self.assertEqual(":neoforge-1.21.1:classes", command[-1])
            self.assertEqual(900, runner.call_args.kwargs["timeout"])
            self.assertTrue((directory/"server-preparation.log").exists())

    @patch("visual_smoke.subprocess.run")
    def test_failed_preflight_does_not_launch_a_server(self, runner):
        runner.return_value = Mock(returncode=1)
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            with self.assertRaisesRegex(RuntimeError, "preparation failed"):
                visual_smoke.prepare_visual_server_build(
                    ["./gradlew"], "classes", directory, directory)
            runner.assert_called_once()


class FaceProgressDeadlineTests(unittest.TestCase):
    def test_heartbeat_is_scoped_to_current_face(self):
        with tempfile.TemporaryDirectory() as temp:
            log = Path(temp)/"client.log"
            log.write_text(
                "SIMPLY_MIRRORS_VISUAL_HEARTBEAT scene=1 ticks=120 reflectionPasses=120\n"
                "SIMPLY_MIRRORS_VISUAL_HEARTBEAT scene=2 ticks=30 reflectionPasses=15\n"
                "SIMPLY_MIRRORS_VISUAL_HEARTBEAT scene=2 ticks=120 reflectionPasses=105\n")
            self.assertEqual((120,105),visual_smoke.latest_visual_heartbeat(log,2))
            self.assertEqual((120,120),visual_smoke.latest_visual_heartbeat(log,1))
            self.assertIsNone(visual_smoke.latest_visual_heartbeat(log,3))

    def test_slow_but_progressing_software_gl_may_finish_after_180s(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp);log=directory/"client.log";log.write_text("")
            clock=[0.0]
            def heartbeat(_log,_scene):
                ticks = min(120,int(clock[0]//42)*30)
                return (ticks,max(0,ticks-15)) if ticks else None
            result=Mock()
            result.exists.side_effect=lambda:clock[0]>=220
            client=Mock();client.poll.return_value=None
            with patch.object(visual_smoke.time,"monotonic",side_effect=lambda:clock[0]), \
                 patch.object(visual_smoke.time,"sleep",side_effect=lambda n:clock.__setitem__(0,clock[0]+n)), \
                 patch.object(visual_smoke,"latest_visual_heartbeat",side_effect=heartbeat), \
                 patch.object(visual_smoke,"capture_client_thread_dump") as dump:
                visual_smoke.wait_for_visual_face(result,"west",2,client,directory,directory,log)
            self.assertGreaterEqual(clock[0],220)
            dump.assert_not_called()

    def test_no_progress_remains_fail_closed_with_diagnostic(self):
        with tempfile.TemporaryDirectory() as temp:
            directory=Path(temp);log=directory/"client.log";log.write_text("")
            clock=[0.0]
            result=Mock();result.exists.return_value=False
            client=Mock();client.poll.return_value=None
            with patch.object(visual_smoke.time,"monotonic",side_effect=lambda:clock[0]), \
                 patch.object(visual_smoke.time,"sleep",side_effect=lambda n:clock.__setitem__(0,clock[0]+n)), \
                 patch.object(visual_smoke,"latest_visual_heartbeat",return_value=None), \
                 patch.object(visual_smoke,"capture_client_thread_dump") as dump:
                with self.assertRaisesRegex(TimeoutError,"west"):
                    visual_smoke.wait_for_visual_face(result,"west",2,client,directory,directory,log)
            self.assertEqual(180,clock[0])
            dump.assert_called_once()


class CrossVersionHeartbeatTests(unittest.TestCase):
    """The Python watchdog relies on this exact render-thread wire contract."""

    VERSIONS = ("1.16.5", "1.20.1", "1.21.1", "26.1.2")

    def test_every_supported_probe_emits_live_scene_heartbeat(self):
        for version in self.VERSIONS:
            with self.subTest(minecraft=version):
                source = (visual_smoke.ROOT / "visual-tests" / version / "java" /
                          "com/nstut/simplymirrors/visualtest/VisualProbeMixin.java").read_text()
                marker = source.index('"SIMPLY_MIRRORS_VISUAL_HEARTBEAT scene="+requested+')
                reset = source.index("simplymirrors$sceneFrames=0;")
                counter = source.index("simplymirrors$sceneFrames++;")
                captured = source.index("if(simplymirrors$captured)return;")
                self.assertLess(reset, counter)
                self.assertLess(counter, marker)
                self.assertLess(marker, captured)
                self.assertIn("simplymirrors$sceneFrames==1 || simplymirrors$sceneFrames%30==0", source)
                self.assertIn('" ticks="+simplymirrors$sceneFrames+', source)
                self.assertIn('" reflectionPasses="+(MirrorRenderer.completedPasses-simplymirrors$startPasses)', source)

    def test_non_120_slow_face_progress_reaches_capture(self):
        # Model the exact heartbeat text emitted by the other MC versioned probes
        # under llvmpipe, not a mocked heartbeat parser.
        for version in ("1.16.5", "1.21.1", "26.1.2"):
            with self.subTest(minecraft=version), tempfile.TemporaryDirectory() as temp:
                directory = Path(temp)
                log = directory / "client.log"
                log.write_text("")
                result = directory / "result.json"
                clock = [0.0]
                def sleep(seconds):
                    clock[0] += seconds
                    if int(clock[0]) in (45, 90, 135, 180, 225):
                        ticks = int(clock[0] / 45) * 30
                        with log.open("a") as output:
                            output.write(
                                f"SIMPLY_MIRRORS_VISUAL_HEARTBEAT scene=2 ticks={ticks} "
                                f"reflectionPasses={ticks - 10}\n")
                    if clock[0] >= 240 and not result.exists():
                        result.write_text("{}")
                client = Mock()
                client.poll.return_value = None
                with patch.object(visual_smoke.time, "monotonic", side_effect=lambda:clock[0]), \
                     patch.object(visual_smoke.time, "sleep", side_effect=sleep), \
                     patch.object(visual_smoke, "capture_client_thread_dump") as diagnostic:
                    visual_smoke.wait_for_visual_face(
                        result, "west", 2, client, directory, directory, log)
                self.assertGreaterEqual(clock[0], 240)
                self.assertEqual((150, 140), visual_smoke.latest_visual_heartbeat(log, 2))
                diagnostic.assert_not_called()

    def test_non_120_stalled_face_reports_dump_and_fails(self):
        for version in ("1.16.5", "1.21.1", "26.1.2"):
            with self.subTest(minecraft=version), tempfile.TemporaryDirectory() as temp:
                directory = Path(temp)
                log = directory / "client.log"
                log.write_text("")
                result = directory / "missing.json"
                clock = [0.0]
                def sleep(seconds):
                    clock[0] += seconds
                    if clock[0] == 40:
                        log.write_text(
                            "SIMPLY_MIRRORS_VISUAL_HEARTBEAT scene=2 ticks=30 reflectionPasses=22\n")
                client = Mock()
                client.poll.return_value = None
                with patch.object(visual_smoke.time, "monotonic", side_effect=lambda:clock[0]), \
                     patch.object(visual_smoke.time, "sleep", side_effect=sleep), \
                     patch.object(visual_smoke, "capture_client_thread_dump") as diagnostic:
                    with self.assertRaisesRegex(TimeoutError, r"west .*ticks=30, passes=22"):
                        visual_smoke.wait_for_visual_face(
                            result, "west", 2, client, directory, directory, log)
                self.assertLess(clock[0], 200)
                diagnostic.assert_called_once_with(directory, directory)


class SceneActivationTests(unittest.TestCase):
    @patch("visual_smoke.time.sleep")
    def test_scene_stays_parked_until_server_teleport_succeeds(self,sleep):
        with tempfile.TemporaryDirectory() as temp:
            directory=Path(temp);visual_smoke.scene_file(directory,-1)
            replies=iter(["No entity was found","Teleported Player380 to 4.5, 65, 0.5"])
            def command(text):
                self.assertEqual("-1",(directory/"visual-scene.txt").read_text())
                self.assertEqual("tp @a 4.5 65 0.5 90 0",text)
                return next(replies)
            rcon=Mock();rcon.command.side_effect=command
            visual_smoke.activate_scene(rcon,directory,3,Mock(poll=Mock(return_value=None)))
            self.assertEqual("3",(directory/"visual-scene.txt").read_text())
            self.assertEqual(2,rcon.command.call_count)

    def test_bad_teleport_does_not_activate_capture(self):
        with tempfile.TemporaryDirectory() as temp:
            directory=Path(temp);visual_smoke.scene_file(directory,-1)
            rcon=Mock();rcon.command.return_value="Incorrect argument for command"
            with self.assertRaisesRegex(RuntimeError,"teleport failed"):
                visual_smoke.activate_scene(rcon,directory,0,Mock(poll=Mock(return_value=None)))
            self.assertEqual("-1",(directory/"visual-scene.txt").read_text())

    def test_exited_client_never_releases_capture(self):
        with tempfile.TemporaryDirectory() as temp:
            directory=Path(temp);visual_smoke.scene_file(directory,-1);rcon=Mock()
            with self.assertRaisesRegex(RuntimeError,"Client exited"):
                visual_smoke.activate_scene(rcon,directory,0,Mock(poll=Mock(return_value=1)))
            rcon.command.assert_not_called()
            self.assertEqual("-1",(directory/"visual-scene.txt").read_text())

class CaptureCompletionTests(unittest.TestCase):
    def test_only_clean_exit_and_passing_images_write_verified_evidence(self):
        for exit_code, failures, error in [(0, {}, None), (1, {}, RuntimeError),
                                           (0, {"north":"missing marker"}, AssertionError),
                                           (None, {}, subprocess.TimeoutExpired)]:
            with self.subTest(exit_code=exit_code, failures=failures), tempfile.TemporaryDirectory() as temp:
                directory=Path(temp);client=Mock();results={"north":{"passes":120}}
                def wait(timeout):
                    self.assertEqual(60,timeout)
                    self.assertFalse((directory/"verified.json").exists())
                    self.assertEqual("6",(directory/"visual-scene.txt").read_text())
                    if exit_code is None:raise subprocess.TimeoutExpired("client",timeout)
                    return exit_code
                client.wait.side_effect=wait
                if error:
                    with self.assertRaises(error):
                        visual_smoke.finish_capture(directory,directory,results,failures,client)
                    self.assertFalse((directory/"verified.json").exists())
                else:
                    visual_smoke.finish_capture(directory,directory,results,failures,client)
                    self.assertEqual(results,json.loads((directory/"verified.json").read_text()))
                self.assertEqual(results,json.loads((directory/"results.json").read_text()))

class ShaderImageTests(unittest.TestCase):
    def image(self,path,colors):
        from PIL import Image,ImageDraw
        image=Image.new("RGB",(960,540),(160,183,204));draw=ImageDraw.Draw(image)
        for index,color in enumerate(colors):draw.rectangle((335+index*96,160,430+index*96,320),fill=color)
        image.save(path)

    def test_shadowed_and_sunlit_shader_bands(self):
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/"reflection.png"
            for colors in [[(32,35,77),(38,71,35),(48,31,36)],[(77,72,161),(145,195,37),(191,53,47)],
                           [(14,19,50),(24,47,25),(31,19,26)]]:
                self.image(path,colors)
                result=visual_smoke.image_check(path,shaders=True)
                self.assertGreater(result["redMeanX"],result["blueMeanX"]+100)

    def test_entity_required_rejects_colored_room_without_player(self):
        from PIL import Image,ImageDraw
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/"reflection.png"
            self.image(path,[(77,72,161),(38,71,35),(191,53,47)])
            visual_smoke.image_check(path,shaders=True)
            with self.assertRaisesRegex(AssertionError,"No reflected player silhouette"):
                visual_smoke.image_check(path,shaders=True,require_entity=True)
            image=Image.open(path);draw=ImageDraw.Draw(image)
            draw.rectangle((465,190,493,280),fill=(79,53,49))
            image.save(path)
            evidence=visual_smoke.image_check(path,shaders=True,require_entity=True)
            self.assertGreater(evidence["entityPixels"],100)

    def test_entity_motion_requires_nontrivial_pixel_change(self):
        from PIL import Image,ImageDraw
        with tempfile.TemporaryDirectory() as temp:
            before=Path(temp)/"idle.png"
            after=Path(temp)/"moving.png"
            self.image(before,[(77,72,161),(38,71,35),(191,53,47)])
            with self.assertRaisesRegex(AssertionError,"swing did not change"):
                visual_smoke.motion_check(before,before)
            image=Image.open(before)
            draw=ImageDraw.Draw(image)
            draw.rectangle((465,200,493,270),fill=(83,61,59))
            image.save(after)
            self.assertGreater(visual_smoke.motion_check(before,after)["movingEntityPixels"],100)

    def test_vertical_reflections_have_face_aware_entity_thresholds(self):
        from PIL import Image,ImageDraw
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/"reflection.png"
            self.image(path,[(77,72,161),(38,71,35),(191,53,47)])
            im=Image.open(path);draw=ImageDraw.Draw(im)
            # Looking into a floor makes the reflected player tiny.
            draw.rectangle((471,216,484,235),fill=(79,53,49))
            im.save(path)
            evidence=visual_smoke.image_check(path,shaders=True,require_entity=True,face_index=5)
            self.assertGreater(evidence["entityPixels"],60)
            # Ceiling reflection can span the adjacent green comparison strip.
            draw.rectangle((460,187,538,280),fill=(79,53,49))
            im.save(path)
            evidence=visual_smoke.image_check(path,shaders=True,require_entity=True,face_index=4)
            self.assertGreater(evidence["entityPixels"],100)

    def test_flat_texture_and_missing_marker_fail(self):
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/"reflection.png"
            for colors in [[(176,204,255)]*3,[(32,35,77),(38,71,35),(160,183,204)]]:
                self.image(path,colors)
                with self.assertRaises(AssertionError):visual_smoke.image_check(path,shaders=True)

    def test_colored_surroundings_cannot_replace_missing_reflection(self):
        from PIL import Image,ImageDraw
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/"reflection.png"
            image=Image.new("RGB",(960,540),(176,204,255));draw=ImageDraw.Draw(image)
            draw.rectangle((0,0,150,539),fill=(20,20,180))
            draw.rectangle((151,0,300,539),fill=(20,180,20))
            draw.rectangle((700,0,959,539),fill=(180,20,20))
            image.save(path)
            with self.assertRaises(AssertionError):visual_smoke.image_check(path,shaders=True)

    def test_unmirrored_band_order_fails(self):
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/"reflection.png"
            self.image(path,[(191,53,47),(145,195,37),(77,72,161)])
            with self.assertRaisesRegex(AssertionError,"handedness"):
                visual_smoke.image_check(path,shaders=True)


class ClientAssetPreflightTests(unittest.TestCase):
    @patch("visual_smoke.time.sleep")
    def test_retry_only_loom_asset_download_failure(self, sleep):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            calls = []
            def run(command, **kwargs):
                calls.append(command)
                self.assertEqual(directory, kwargs["cwd"])
                self.assertEqual(subprocess.STDOUT, kwargs["stderr"])
                self.assertIs(False, kwargs["check"])
                if len(calls) == 1:
                    kwargs["stdout"].write("net.fabricmc.loom.util.download.DownloadException: Failed to download\n")
                    return Mock(returncode=1)
                kwargs["stdout"].write("BUILD SUCCESSFUL\n")
                return Mock(returncode=0)
            with patch("visual_smoke.subprocess.run", side_effect=run):
                visual_smoke.prepare_visual_client_assets(
                    ["./gradlew"], ":fabric-1.16.5:downloadAssets", directory, directory)
            self.assertEqual(2, len(calls))
            self.assertEqual([ "./gradlew", ":fabric-1.16.5:downloadAssets"], calls[0])
            self.assertEqual(calls[0], calls[1])
            self.assertIn("DownloadException", (directory/"asset-download-1.log").read_text())
            self.assertIn("BUILD SUCCESSFUL", (directory/"asset-download-2.log").read_text())
            sleep.assert_called_once_with(3)

    @patch("visual_smoke.time.sleep")
    def test_non_download_failure_is_not_retried(self, sleep):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            def run(command, **kwargs):
                kwargs["stdout"].write("Could not find task downloadAssets\n")
                return Mock(returncode=1)
            with patch("visual_smoke.subprocess.run", side_effect=run) as runner:
                with self.assertRaisesRegex(RuntimeError, "preflight failed"):
                    visual_smoke.prepare_visual_client_assets(
                        ["./gradlew"], "downloadAssets", directory, directory)
            runner.assert_called_once()
            sleep.assert_not_called()

    @patch("visual_smoke.time.sleep")
    def test_repeated_download_failures_are_bounded(self, sleep):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            def run(command, **kwargs):
                kwargs["stdout"].write("net.fabricmc.loom.util.download.DownloadException: Failed to download\n")
                return Mock(returncode=1)
            with patch("visual_smoke.subprocess.run", side_effect=run) as runner:
                with self.assertRaisesRegex(RuntimeError, "attempt 3/3"):
                    visual_smoke.prepare_visual_client_assets(
                        ["./gradlew"], "downloadAssets", directory, directory)
            self.assertEqual(3, runner.call_count)
            self.assertEqual(2, sleep.call_count)
            for attempt in (1, 2, 3):
                self.assertTrue((directory/f"asset-download-{attempt}.log").exists())


class ExternalModpackReproductionTests(unittest.TestCase):
    def test_extra_render_mod_is_copied_only_into_disposable_profile(self):
        import hashlib
        import zipfile
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);source=root/'shader-compat.jar'
            with zipfile.ZipFile(source,'w') as jar:
                jar.writestr('META-INF/mods.toml','[[mods]]\nmodId="shader_compat"\n')
            client=root/'isolated-client'
            manifest=visual_smoke.install_extra_client_mods(client,[source])
            copy=client/'mods'/source.name
            self.assertTrue(copy.exists())
            self.assertEqual(source.read_bytes(),copy.read_bytes())
            self.assertEqual(hashlib.sha256(source.read_bytes()).hexdigest(),manifest[0]['sha256'])
            self.assertFalse((root/'mods').exists())
            self.assertEqual([],visual_smoke.install_extra_client_mods(client,[]))

    def test_duplicate_or_invalid_mod_leaves_profile_unchanged(self):
        import zipfile
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);source=root/'render-addon.jar';client=root/'test-client'
            with zipfile.ZipFile(source,'w') as jar:jar.writestr('fabric.mod.json','{"id":"render-addon"}')
            broken=root/'not-a-jar.jar';broken.write_text('plain text')
            for batch, message in [([source,source],'Duplicate'),([source,broken],'Not a Minecraft mod JAR')]:
                with self.subTest(message=message):
                    with self.assertRaisesRegex(ValueError,message):
                        visual_smoke.install_extra_client_mods(client,batch)
                    self.assertFalse((client/'mods'/source.name).exists())
            # Existing files must survive rejection unchanged.
            mods=client/'mods';mods.mkdir(parents=True)
            existing=mods/source.name;existing.write_bytes(b'existing')
            with self.assertRaisesRegex(ValueError,'Duplicate'):
                visual_smoke.install_extra_client_mods(client,[source])
            self.assertEqual(b'existing',existing.read_bytes())

    def test_staging_failure_does_not_install_partial_batch(self):
        import zipfile
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);client=root/'test-client'
            jars=[root/'first.jar',root/'second.jar']
            for path in jars:
                with zipfile.ZipFile(path,'w') as jar:jar.writestr('fabric.mod.json','{}')
            original=visual_smoke.shutil.copy2
            calls=0
            def fail_second(*args,**kwargs):
                nonlocal calls
                calls+=1
                if calls==2:raise OSError('staging failure')
                return original(*args,**kwargs)
            with patch('visual_smoke.shutil.copy2',side_effect=fail_second):
                with self.assertRaisesRegex(OSError,'staging failure'):
                    visual_smoke.install_extra_client_mods(client,jars)
            self.assertFalse((client/'mods').exists())



class VisualThreadDumpDiscoveryTests(unittest.TestCase):
    @unittest.skipIf(os.name == 'nt', 'Synthetic /proc cwd symlinks require Unix semantics')
    def test_isolated_client_cwd_matches_without_client_path_in_jvm_args(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            proc = root / "proc"
            proc.mkdir()
            client = root / "visual-client"
            client.mkdir()
            unrelated = root / "unrelated-game"
            unrelated.mkdir()
            for pid, cwd, cmdline in [
                (101, client, b"/usr/lib/jvm/bin/java\x00net.minecraft.client.main.Main\x00"),
                (102, unrelated, b"/usr/lib/jvm/bin/java\x00net.minecraft.client.main.Main\x00"),
                (103, client, b"/usr/bin/python3\x00test_fixture.py\x00"),
                (104, unrelated, b"/usr/lib/jvm/bin/java\x00-Dvisual.client=" +
                    str(client.resolve()).encode() + b"\x00"),
            ]:
                entry = proc / str(pid)
                entry.mkdir()
                (entry / "cmdline").write_bytes(cmdline)
                (entry / "cwd").symlink_to(cwd, target_is_directory=True)
            # Also cover a launch whose working directory is unavailable:
            # the explicitly scoped client path still identifies it.
            entry = proc / "105"
            entry.mkdir()
            (entry / "cmdline").write_bytes(b"/usr/bin/java\x00-Dclient=" +
                                           str(client.resolve()).encode() + b"\x00")
            # Do not confuse an unrelated client whose argument merely
            # contains this directory as a prefix.
            entry = proc / "106"
            entry.mkdir()
            (entry / "cmdline").write_bytes(
                b"/usr/bin/java\x00-Dclient=" + str(client.resolve()).encode() + b"-other\x00")
            (entry / "cwd").symlink_to(unrelated, target_is_directory=True)
            self.assertEqual(
                ["101", "104", "105"],
                [pid for pid, _ in visual_smoke.fixture_java_processes(client, proc)]
            )

    @unittest.skipIf(os.name == 'nt', 'Synthetic /proc cwd symlinks require Unix semantics')
    def test_literal_backslash_zero_is_not_a_proc_argument_separator(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);client=root/"client";client.mkdir()
            entry=root/"proc"/"100";entry.mkdir(parents=True)
            (entry/"cwd").symlink_to(client,target_is_directory=True)
            (entry/"cmdline").write_bytes(b"/usr/bin/java"+bytes([92,48])+b"Main")
            self.assertEqual([],visual_smoke.fixture_java_processes(client,root/"proc"))

    def test_missing_proc_or_process_cannot_match_unrelated_java(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.assertEqual([], visual_smoke.fixture_java_processes(
                root / "client", root / "missing-proc"))
            proc = root / "proc"
            proc.mkdir()
            (proc / "not-a-pid").mkdir()
            self.assertEqual([], visual_smoke.fixture_java_processes(
                root / "client", proc))




if __name__=="__main__":unittest.main()
