"""Publish Solventa without logging provider responses or secrets."""

import json
import hashlib
import os
from pathlib import Path
import re
import sys
import uuid
from urllib.error import HTTPError
from urllib.request import HTTPRedirectHandler, Request, build_opener
from zipfile import ZipFile


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def upload_request(apk, public_key, token, sha, version=None):
    if not re.fullmatch(r"[A-Za-z0-9_-]{6,128}", public_key):
        raise ValueError("Expected the public key of an existing Appetize app")
    if not token or any(character in token for character in "\r\n"):
        raise ValueError("Missing or invalid Appetize token")
    if not re.fullmatch(r"[a-f0-9]{40}", sha):
        raise ValueError("Expected a full Git commit SHA")
    if version is not None and not re.fullmatch(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", version):
        raise ValueError("Expected a stable release version")
    with ZipFile(apk) as archive:
        if "AndroidManifest.xml" not in archive.namelist():
            raise ValueError("The artifact is not an APK")
    boundary = "solventa-" + uuid.uuid4().hex
    note = "master " + sha
    if version is not None:
        note = version + " | " + note
    fields = {"platform": "android", "fileType": "apk", "note": note,
              "timeout": "60", "timeLimit": "180", "maxConcurrent": "2", "useLastFrame": "false"}
    parts = []
    for name, value in fields.items():
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode())
    parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="Solventa.apk"\r\nContent-Type: application/vnd.android.package-archive\r\n\r\n'.encode())
    parts.extend([Path(apk).read_bytes(), f"\r\n--{boundary}--\r\n".encode()])
    return Request(
        "https://api.appetize.io/v1/apps/" + public_key,
        data=b"".join(parts), method="POST",
        headers={"X-API-KEY": token, "Content-Type": "multipart/form-data; boundary=" + boundary},
    )


def source_commit():
    if os.environ.get("GITHUB_EVENT_NAME") == "workflow_run":
        event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text(encoding="utf-8"))
        run = event.get("workflow_run", {})
        if (run.get("event") != "push" or run.get("head_branch") != "master"
                or run.get("conclusion") != "success" or run.get("status") != "completed"
                or not os.environ.get("GITHUB_REPOSITORY")
                or run.get("head_repository", {}).get("full_name") != os.environ["GITHUB_REPOSITORY"]
                or not re.fullmatch(r"[a-f0-9]{40}", run.get("head_sha", ""))):
            raise ValueError("Only successful CI from trusted master may publish")
        return run["head_sha"]
    if os.environ.get("GITHUB_REF") != "refs/heads/master" or os.environ.get("GITHUB_EVENT_NAME") not in ("push", "workflow_dispatch"):
        raise ValueError("Only trusted master runs may publish")
    return os.environ.get("GITHUB_SHA", "")


def main():
    sha = source_commit()
    if len(sys.argv) not in (2, 3):
        raise ValueError("Expected an APK and optional published release metadata")
    public_key = os.environ.get("APPETIZE_PUBLIC_KEY", "")
    version = None
    if len(sys.argv) == 3:
        metadata = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
        sha, version = metadata["commit"], metadata["version"]
        if hashlib.sha256(Path(sys.argv[1]).read_bytes()).hexdigest() != metadata["sha256"]:
            raise ValueError("APK checksum does not match the published release")
    request = upload_request(sys.argv[1], public_key, os.environ.get("APPETIZE_API_TOKEN", ""), sha, version)
    try:
        with build_opener(NoRedirect()).open(request, timeout=120) as response:
            data = response.read(65_537)
            if len(data) > 65_536:
                raise ValueError("Unexpected provider response size")
            result = json.loads(data)
    except HTTPError as error:
        # Provider bodies may contain private app metadata: report only the status.
        raise RuntimeError(f"Appetize upload failed (HTTP {error.code})") from None
    if not isinstance(result, dict) or result.get("publicKey") != public_key:
        raise ValueError("Provider returned a different app; identity not confirmed")
    url = "https://appetize.io/app/" + public_key
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as summary:
        summary.write(f"## Solventa\n\n[Abrir Solventa]({url})\n")
        if version is not None:
            summary.write(f"\nVersión: `{version}`\n")
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            output.write(f"url={url}\n")
    print("Solventa publicada correctamente.")


if __name__ == "__main__":
    main()
