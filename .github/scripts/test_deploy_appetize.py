import importlib.util
from contextlib import redirect_stdout
from io import BytesIO, StringIO
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
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
        self.summary = Path(self.directory.name) / "summary.md"
        self.env = {
            "GITHUB_REF": "refs/heads/master",
            "GITHUB_EVENT_NAME": "push",
            "GITHUB_SHA": "a" * 40,
            "GITHUB_STEP_SUMMARY": str(self.summary),
            "APPETIZE_PUBLIC_KEY": "test-public-key",
            "APPETIZE_API_TOKEN": "test-token",
        }

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

    def test_success_writes_only_the_confirmed_app_url_and_commit(self):
        output = StringIO()
        with redirect_stdout(output), patch.dict(deploy.os.environ, self.env, clear=True), \
                patch.object(deploy.sys, "argv", ["deploy_appetize.py", str(self.apk)]), \
                patch.object(deploy, "build_opener") as opener:
            opener.return_value.open.return_value = BytesIO(b'{"publicKey":"test-public-key"}')
            deploy.main()
        summary = self.summary.read_text(encoding="utf-8")
        self.assertIn("https://appetize.io/app/test-public-key", summary)
        self.assertIn("a" * 40, summary)
        self.assertNotIn("test-token", summary)
        self.assertNotIn("test-token", output.getvalue())

    def test_wrong_app_identity_cannot_publish_a_summary(self):
        with patch.dict(deploy.os.environ, self.env, clear=True), \
                patch.object(deploy.sys, "argv", ["deploy_appetize.py", str(self.apk)]), \
                patch.object(deploy, "build_opener") as opener:
            opener.return_value.open.return_value = BytesIO(b'{"publicKey":"other-public-key"}')
            with self.assertRaisesRegex(ValueError, "different app"):
                deploy.main()
        self.assertFalse(self.summary.exists())

    def test_oversized_response_cannot_publish_a_summary(self):
        with patch.dict(deploy.os.environ, self.env, clear=True), \
                patch.object(deploy.sys, "argv", ["deploy_appetize.py", str(self.apk)]), \
                patch.object(deploy, "build_opener") as opener:
            opener.return_value.open.return_value = BytesIO(b"x" * 65_537)
            with self.assertRaisesRegex(ValueError, "response size"):
                deploy.main()
        self.assertFalse(self.summary.exists())

    def test_http_failure_does_not_expose_the_provider_body(self):
        with patch.dict(deploy.os.environ, self.env, clear=True), \
                patch.object(deploy.sys, "argv", ["deploy_appetize.py", str(self.apk)]), \
                patch.object(deploy, "build_opener") as opener:
            opener.return_value.open.side_effect = HTTPError(
                "https://api.appetize.io/v1/apps/test-public-key", 401, "Unauthorized", {},
                BytesIO(b"private-provider-body test-token"),
            )
            with self.assertRaisesRegex(RuntimeError, r"HTTP 401") as failure:
                deploy.main()
        self.assertNotIn("private-provider-body", str(failure.exception))
        self.assertNotIn("test-token", str(failure.exception))
        self.assertFalse(self.summary.exists())

    def test_redirects_are_not_followed(self):
        request = deploy.upload_request(self.apk, "test-public-key", "test-token", "a" * 40)
        self.assertIsNone(deploy.NoRedirect().redirect_request(
            request, None, 302, "Found", {}, "https://other.example/",
        ))


if __name__ == "__main__":
    unittest.main()
