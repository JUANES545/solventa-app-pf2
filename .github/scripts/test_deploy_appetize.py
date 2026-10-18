import importlib.util
import hashlib
import json
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


class AppPublicationTests(unittest.TestCase):
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
        self.assertIn(b'filename="Solventa.apk"', request.data)

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
        self.assertIn("Abrir Solventa", summary)
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

    def test_retry_uses_the_original_release_commit_and_exposes_only_the_public_url(self):
        metadata = Path(self.directory.name) / "release.json"
        metadata.write_text(json.dumps({"commit": "b" * 40, "version": "0.2.0",
                                       "sha256": hashlib.sha256(self.apk.read_bytes()).hexdigest()}))
        outputs = Path(self.directory.name) / "outputs"
        with redirect_stdout(StringIO()), \
                patch.dict(deploy.os.environ, self.env | {"GITHUB_OUTPUT": str(outputs)}, clear=True), \
                patch.object(deploy.sys, "argv", ["deploy_appetize.py", str(self.apk), str(metadata)]), \
                patch.object(deploy, "build_opener") as opener:
            opener.return_value.open.return_value = BytesIO(b'{"publicKey":"test-public-key"}')
            deploy.main()
            request = opener.return_value.open.call_args.args[0]
        self.assertIn(b"master " + b"b" * 40, request.data)
        self.assertIn("0.2.0", self.summary.read_text())
        self.assertEqual(outputs.read_text(), "url=https://appetize.io/app/test-public-key\n")

    def test_wrong_release_checksum_is_rejected_before_any_upload(self):
        metadata = Path(self.directory.name) / "release.json"
        metadata.write_text(json.dumps({"commit": "b" * 40, "version": "0.2.0", "sha256": "0" * 64}))
        with patch.dict(deploy.os.environ, self.env, clear=True), \
                patch.object(deploy.sys, "argv", ["deploy_appetize.py", str(self.apk), str(metadata)]), \
                patch.object(deploy, "build_opener") as opener:
            with self.assertRaisesRegex(ValueError, "checksum"):
                deploy.main()
            opener.assert_not_called()
        self.assertFalse(self.summary.exists())

    def test_workflow_run_requires_successful_push_from_the_same_master(self):
        event = Path(self.directory.name) / "event.json"
        run = {"event": "push", "head_branch": "master", "head_sha": "b" * 40,
               "status": "completed", "conclusion": "success", "head_repository": {"full_name": "owner/repo"}}
        env = self.env | {"GITHUB_EVENT_NAME": "workflow_run", "GITHUB_REF": "refs/heads/develop",
                          "GITHUB_REPOSITORY": "owner/repo", "GITHUB_EVENT_PATH": str(event)}
        event.write_text(json.dumps({"workflow_run": run}))
        with patch.dict(deploy.os.environ, env, clear=True):
            self.assertEqual(deploy.source_commit(), "b" * 40)
            for change in ({"event": "pull_request"}, {"head_branch": "develop"}, {"conclusion": "failure"},
                           {"head_repository": {"full_name": "fork/repo"}}, {"status": "in_progress"}):
                event.write_text(json.dumps({"workflow_run": run | change}))
                with self.assertRaises(ValueError):
                    deploy.source_commit()
        self.assertFalse(self.summary.exists())

    def test_extra_arguments_cannot_silently_skip_release_metadata(self):
        with patch.dict(deploy.os.environ, self.env, clear=True), \
                patch.object(deploy.sys, "argv", ["deploy_appetize.py", str(self.apk), "release.json", "extra"]), \
                patch.object(deploy, "build_opener") as opener:
            with self.assertRaisesRegex(ValueError, "optional published release metadata"):
                deploy.main()
            opener.assert_not_called()


if __name__ == "__main__":
    unittest.main()
