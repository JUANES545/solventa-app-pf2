import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile

spec = importlib.util.spec_from_file_location("deploy", Path(__file__).with_name("deploy_appetize.py"))
deploy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deploy)


class PreviewRequestTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.apk = Path(self.directory.name) / "test.apk"
        with ZipFile(self.apk, "w") as archive:
            archive.writestr("AndroidManifest.xml", "test manifest")

    def test_updates_only_the_existing_fixed_host_app(self):
        request = deploy.upload_request(self.apk, "test-public-key", "test-token", "a" * 40)
        self.assertEqual(request.full_url, "https://api.appetize.io/v1/apps/test-public-key")
        self.assertEqual(request.method, "POST")
        self.assertNotIn(b"test-token", request.data)
        self.assertIn(b"AndroidManifest.xml", request.data)
        self.assertIn(b"master " + b"a" * 40, request.data)

    def test_rejects_missing_or_path_like_app_keys(self):
        for key in ("", "../apps", "https://other.example", "test/key"):
            with self.assertRaises(ValueError):
                deploy.upload_request(self.apk, key, "test-token", "a" * 40)

    def test_rejects_empty_token_header_injection_and_invalid_sha(self):
        for token, sha in (("", "a" * 40), ("bad\r\nheader", "a" * 40), ("token", "master")):
            with self.assertRaises(ValueError):
                deploy.upload_request(self.apk, "test-public-key", token, sha)

    def test_rejects_an_archive_without_android_manifest(self):
        with ZipFile(self.apk, "w") as archive:
            archive.writestr("not-an-app.txt", "test")
        with self.assertRaises(ValueError):
            deploy.upload_request(self.apk, "test-public-key", "test-token", "a" * 40)

    def test_pull_request_cannot_publish_even_with_master_ref(self):
        with patch.dict(deploy.os.environ, {"GITHUB_REF": "refs/heads/master", "GITHUB_EVENT_NAME": "pull_request"}, clear=True):
            with self.assertRaises(ValueError):
                deploy.main()

    def test_develop_cannot_publish(self):
        with patch.dict(deploy.os.environ, {"GITHUB_REF": "refs/heads/develop", "GITHUB_EVENT_NAME": "push"}, clear=True):
            with self.assertRaises(ValueError):
                deploy.main()


if __name__ == "__main__":
    unittest.main()
