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

class ShaderImageTests(unittest.TestCase):
    def image(self,path,colors):
        from PIL import Image,ImageDraw
        image=Image.new("RGB",(960,540),(160,183,204));draw=ImageDraw.Draw(image)
        for index,color in enumerate(colors):draw.rectangle((335+index*96,160,430+index*96,320),fill=color)
        image.save(path)

    def test_shadowed_and_sunlit_shader_bands(self):
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/"reflection.png"
            for colors in [[(32,35,77),(38,71,35),(48,31,36)],[(77,72,161),(145,195,37),(191,53,47)]]:
                self.image(path,colors)
                result=visual_smoke.image_check(path,shaders=True)
                self.assertGreater(result["redMeanX"],result["blueMeanX"]+100)

    def test_flat_texture_and_missing_marker_fail(self):
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/"reflection.png"
            for colors in [[(176,204,255)]*3,[(32,35,77),(38,71,35),(160,183,204)]]:
                self.image(path,colors)
                with self.assertRaises(AssertionError):visual_smoke.image_check(path,shaders=True)

    def test_unmirrored_band_order_fails(self):
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/"reflection.png"
            self.image(path,[(191,53,47),(145,195,37),(77,72,161)])
            with self.assertRaisesRegex(AssertionError,"handedness"):
                visual_smoke.image_check(path,shaders=True)

if __name__=="__main__":unittest.main()
