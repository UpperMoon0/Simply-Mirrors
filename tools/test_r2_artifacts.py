import io
import json
from pathlib import Path
import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
import zipfile
from r2_artifacts import namespace, upload, download, extract


class ClientError(Exception):
    def __init__(self, response, operation):
        self.response = response
        super().__init__(operation)


class Store:
    def __init__(self):
        self.objects = {}

    def put_object(self, Bucket, Key, Body, **kwargs):
        if Key in self.objects and kwargs.get('IfNoneMatch') == '*':
            raise ClientError({'Error': {'Code': 'PreconditionFailed'},
                               'ResponseMetadata': {'HTTPStatusCode': 412}}, 'PutObject')
        self.objects[Key] = Body.read() if hasattr(Body, 'read') else Body

    def get_object(self, Bucket, Key):
        return {'Body': io.BytesIO(self.objects[Key])}

    def get_paginator(self, name):
        return self

    def paginate(self, Bucket, Prefix):
        # Exercise pagination rather than assuming one page.
        for key in sorted(self.objects):
            if key.startswith(Prefix):
                yield {'Contents': [{'Key': key}]}

    def download_file(self, bucket, key, path):
        Path(path).write_bytes(self.objects[key])


class Artifacts(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.store = Store()
        self.prefix = namespace('owner/repo', 'build', '123', '1', 'a' * 40)
        (self.root / 'a.jar').write_bytes(b'production jar')

    def put(self, name='build-a', patterns='*.jar'):
        upload(self.store, 'private', self.prefix, name, patterns, root=self.root)

    def get(self, pattern='build-*', expected=None):
        download(self.store, 'private', self.prefix, pattern, self.root / 'output', expected=expected)

    def test_round_trip(self):
        self.put()
        self.get(expected=['build-a'])
        self.assertEqual((self.root / 'output/build-a/a.jar').read_bytes(), b'production jar')

    def test_parallel_isolation(self):
        with ThreadPoolExecutor(max_workers=8) as pool:
            list(pool.map(self.put, [f'build-{i}' for i in range(8)]))
        self.get(expected=[f'build-{i}' for i in range(8)])
        self.assertEqual(len(list((self.root / 'output').rglob('*.jar'))), 8)

    def test_duplicate_upload_rejected(self):
        self.put()
        with self.assertRaises(ClientError):
            self.put()

    def test_partial_archive_not_discoverable(self):
        self.store.objects[self.prefix + 'build-a/orphan.zip'] = b'partial'
        with self.assertRaisesRegex(ValueError, 'Missing or expired'):
            self.get()

    def test_missing(self):
        with self.assertRaisesRegex(ValueError, 'Missing or expired'):
            self.get()

    def test_incomplete_matrix(self):
        self.put()
        with self.assertRaisesRegex(ValueError, 'Missing or expired'):
            self.get(expected=['build-a', 'build-b'])

    def test_corruption(self):
        self.put()
        key = next(k for k in self.store.objects if k.endswith('.zip'))
        self.store.objects[key] += b'corruption'
        with self.assertRaisesRegex(ValueError, 'SHA-256 mismatch'):
            self.get()
        self.assertFalse((self.root / 'output').exists())

    def test_manifest_cannot_point_outside_namespace(self):
        self.put()
        key = next(k for k in self.store.objects if k.endswith('manifest.json'))
        m = json.loads(self.store.objects[key]); m['key'] = 'other/run.zip'
        self.store.objects[key] = json.dumps(m).encode()
        with self.assertRaisesRegex(ValueError, 'Invalid R2 artifact manifest'):
            self.get()

    def test_exclusions(self):
        (self.root / 'a-sources.jar').write_bytes(b'source')
        self.put(patterns='*.jar\n!*-sources.jar')
        self.get()
        self.assertEqual(len(list((self.root / 'output').rglob('*.jar'))), 1)

    def test_no_files(self):
        with self.assertRaisesRegex(ValueError, 'No artifact files'):
            self.put(patterns='nothing/*')
        upload(self.store, 'private', self.prefix, 'optional', 'nothing/*', 'ignore', self.root)

    def test_namespaces_isolate_attempt_workflow_sha(self):
        values = {self.prefix, namespace('owner/repo', 'build', '123', '2', 'a' * 40),
                  namespace('owner/repo', 'visual', '123', '1', 'a' * 40),
                  namespace('owner/repo', 'build', '123', '1', 'b' * 40)}
        self.assertEqual(len(values), 4)

    def test_failed_downstream_job_rerun_reuses_attempt_one_producers(self):
        # Eight producers succeeded on attempt 1; only release-package runs on 2.
        names = [f'build-{i}' for i in range(8)]
        for name in names:
            self.put(name)
        attempt_two = namespace('owner/repo', 'build', '123', '2', 'a' * 40)
        download(self.store, 'private', attempt_two, 'build-*',
                 self.root / 'output', expected=names)
        self.assertEqual(8, len(list((self.root / 'output').rglob('a.jar'))))

    def test_newest_successful_same_commit_producer_wins(self):
        self.put()
        attempt_two = namespace('owner/repo', 'build', '123', '2', 'a' * 40)
        (self.root / 'a.jar').write_bytes(b'rebuilt jar')
        upload(self.store, 'private', attempt_two, 'build-a', '*.jar', root=self.root)
        attempt_three = namespace('owner/repo', 'build', '123', '3', 'a' * 40)
        download(self.store, 'private', attempt_three, 'build-*', self.root / 'output',
                 expected=['build-a'])
        self.assertEqual((self.root / 'output/build-a/a.jar').read_bytes(), b'rebuilt jar')

    def test_retry_does_not_accept_other_commit_or_run_or_workflow(self):
        for other in [
            namespace('owner/repo', 'build', '123', '1', 'b' * 40),
            namespace('owner/repo', 'build', '124', '1', 'a' * 40),
            namespace('owner/repo', 'shader', '123', '1', 'a' * 40),
        ]:
            with self.subTest(other=other):
                store = Store()
                upload(store, 'private', other, 'build-a', '*.jar', root=self.root)
                with self.assertRaisesRegex(ValueError, 'Missing or expired'):
                    download(store, 'private', self.prefix, 'build-*',
                             self.root / 'output')

    def test_retry_cannot_read_producers_from_future_attempt(self):
        future = namespace('owner/repo', 'build', '123', '3', 'a' * 40)
        upload(self.store, 'private', future, 'build-a', '*.jar', root=self.root)
        current = namespace('owner/repo', 'build', '123', '2', 'a' * 40)
        with self.assertRaisesRegex(ValueError, 'Missing or expired'):
            download(self.store, 'private', current, 'build-*', self.root / 'output')

    def test_corrupt_newer_attempt_does_not_silently_fall_back(self):
        self.put()
        current = namespace('owner/repo', 'build', '123', '2', 'a' * 40)
        upload(self.store, 'private', current, 'build-a', '*.jar', root=self.root)
        key = current + 'build-a/manifest.json'
        manifest = json.loads(self.store.objects[key])
        manifest['key'] = 'untrusted.zip'
        self.store.objects[key] = json.dumps(manifest).encode()
        with self.assertRaisesRegex(ValueError, 'Invalid R2 artifact manifest'):
            download(self.store, 'private', current, 'build-*', self.root / 'output')
        self.assertFalse((self.root / 'output').exists())

    def test_path_traversal_rejected(self):
        for name in ['../escape', '/absolute', 'C:/absolute']:
            archive = self.root / 'evil.zip'
            with zipfile.ZipFile(archive, 'w') as z:
                z.writestr(name, 'bad')
            with self.assertRaisesRegex(ValueError, 'Unsafe archive'):
                extract(archive, self.root / 'output')

    def test_hidden_secrets_rejected(self):
        (self.root / '.env').write_text('secret')
        with self.assertRaisesRegex(ValueError, 'Hidden files'):
            self.put(patterns='.env')

    def test_merge_release_bundle(self):
        self.put()
        download(self.store, 'private', self.prefix, 'build-a', self.root / 'output', merge=True)
        self.assertTrue((self.root / 'output/a.jar').exists())


if __name__ == '__main__':
    unittest.main()
