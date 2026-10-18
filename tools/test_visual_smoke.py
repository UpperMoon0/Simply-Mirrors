"""Regression checks for server-authorized visual scene transitions."""
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
import visual_smoke

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

if __name__=="__main__":unittest.main()
