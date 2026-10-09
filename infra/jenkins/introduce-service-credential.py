#!/usr/bin/env python3
"""Accept the service credential only in an interactive private terminal."""

import getpass
import json
import os
from pathlib import Path
import pwd
import sys
import tempfile


def main():
    if not sys.stdin.isatty():
        print("Use your own private interactive Session Manager terminal.", file=sys.stderr)
        return 1
    user = "jenkins-lifecycle"
    if os.geteuid() != pwd.getpwnam(user).pw_uid:
        print("Run this helper as the lifecycle OS user.", file=sys.stderr)
        return 1
    path = Path("/etc/jenkins-pilot/read-credentials.json")
    if path.exists() and input("Replace the existing service credential? [y/N] ").strip().lower() != "y":
        print("Existing credential preserved.")
        return 0
    token = getpass.getpass("API token for jenkins-lifecycle (hidden input): ")
    if not token:
        print("No credential saved.", file=sys.stderr)
        return 1
    temporary = None
    try:
        descriptor, temporary = tempfile.mkstemp(dir=path.parent, prefix=".credential-")
        with os.fdopen(descriptor, "w") as stream:
            json.dump({"user": user, "token": token}, stream)
        os.chmod(temporary, 0o600)
        os.replace(temporary, path)
        temporary = None
    finally:
        if temporary is not None:
            os.unlink(temporary)
    print("Service credential saved privately with mode 0600.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
