"""Reject incompatible locks and unverified downloads before a shader test starts."""
import copy
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import shader_stack


class ShaderStackTests(unittest.TestCase):
    def test_lock_covers_each_supported_loader_and_game(self):
        targets = json.loads((shader_stack.ROOT / 'tools/targets.json').read_text())
        self.assertEqual({t['target'] for t in targets}, set(shader_stack.load_lock()['stacks']))

    def test_incompatible_or_unsafe_lock_rejected(self):
        valid = shader_stack.load_lock()
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'lock.json'
            for field, value in [('game_versions', ['0.0.0']), ('loaders', ['wrong']),
                                 ('url', 'https://example.com/untrusted.jar'), ('filename', '../escape.jar')]:
                data = copy.deepcopy(valid)
                next(iter(data['stacks'].values()))[0][field] = value
                path.write_text(json.dumps(data))
                with patch.object(shader_stack, 'LOCK', path), self.assertRaises(ValueError):
                    shader_stack.load_lock()

    def test_corrupt_cache_and_download_never_accepted(self):
        expected = b'verified bytes'
        artifact = dict(filename='fixture.jar', url='https://cdn.modrinth.com/fixture.jar',
                        sha512=hashlib.sha512(expected).hexdigest())
        with tempfile.TemporaryDirectory() as temp:
            cache = Path(temp)
            (cache / 'fixture.jar').write_bytes(b'corrupt cache')
            with patch('shader_stack.urllib.request.urlopen', return_value=io.BytesIO(b'corrupt network')):
                with self.assertRaisesRegex(ValueError, 'checksum mismatch'):
                    shader_stack.download(artifact, cache)
            self.assertEqual(b'corrupt cache', (cache / 'fixture.jar').read_bytes())
            with patch('shader_stack.urllib.request.urlopen', return_value=io.BytesIO(expected)):
                self.assertEqual(expected, shader_stack.download(artifact, cache).read_bytes())


if __name__ == '__main__':
    unittest.main()
