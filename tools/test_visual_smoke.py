"""Regression checks for server-authorized visual scene transitions."""
from pathlib import Path
import tempfile
import subprocess
import json
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


class ExternalModpackReproductionTests(unittest.TestCase):
    def test_extra_render_mod_is_copied_only_into_disposable_profile(self):
        import hashlib
        import zipfile
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);source=root/'shader-compat.jar'
            with zipfile.ZipFile(source,'w') as jar:
                jar.writestr('META-INF/mods.toml','[[mods]]\\nmodId="shader_compat"\\n')
            client=root/'isolated-client'
            manifest=visual_smoke.install_extra_client_mods(client,[source])
            copy=client/'mods'/source.name
            self.assertTrue(copy.exists())
            self.assertEqual(source.read_bytes(),copy.read_bytes())
            self.assertEqual(hashlib.sha256(source.read_bytes()).hexdigest(),manifest[0]['sha256'])
            self.assertFalse((root/'mods').exists())
            self.assertEqual([],visual_smoke.install_extra_client_mods(client,[]))

    def test_duplicate_or_invalid_mod_cannot_replace_a_profile_jar(self):
        import zipfile
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);source=root/'render-addon.jar';client=root/'test-client'
            with zipfile.ZipFile(source,'w') as jar:jar.writestr('fabric.mod.json','{"id":"render-addon"}')
            with self.assertRaisesRegex(ValueError,'Duplicate'):
                visual_smoke.install_extra_client_mods(client,[source,source])
            self.assertEqual(source.read_bytes(),(client/'mods'/source.name).read_bytes())
            broken=root/'not-a-jar.jar';broken.write_text('plain text')
            with self.assertRaisesRegex(ValueError,'Not a Minecraft mod JAR'):
                visual_smoke.install_extra_client_mods(client,[broken])


if __name__=="__main__":unittest.main()
