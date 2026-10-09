#!/usr/bin/env python3
"""Start the fixed Android agent for queued work and stop it after inactivity."""

import base64
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import time
import sys
from urllib.request import HTTPRedirectHandler, Request, build_opener
from urllib.parse import quote, urlsplit


def decide_action(instance_state, queued, busy, now, last_active, expired=False):
    if expired:
        return "stop" if instance_state in {"running", "pending"} else "none"
    if instance_state == "stopped":
        return "start" if queued else "none"
    if instance_state != "running":
        return "none"
    if queued or busy:
        return "none"
    return "stop" if now - last_active >= 300 else "none"


def belongs_to_job(task, job_name):
    full_name = task.get("fullName", "")
    if full_name == job_name or full_name.startswith(job_name + "/"):
        return True
    if task.get("name") == job_name:
        return True
    return urlsplit(task.get("url", "")).path.lstrip("/").startswith("job/" + quote(job_name, safe="") + "/")


def read_demand(queue, node, job_name):
    items = queue.get("items")
    if not isinstance(items, list) or not all(isinstance(item, dict) for item in items):
        raise ValueError("Queue response is incomplete")
    if not isinstance(node.get("offline"), bool) or not isinstance(node.get("idle"), bool):
        raise ValueError("Node response is incomplete")
    if not all(isinstance(item.get("task"), dict) for item in items):
        raise ValueError("Queue task metadata is incomplete")
    queued = any(belongs_to_job(item["task"], job_name) for item in items)
    busy = not node["offline"] and not node["idle"]
    return queued, busy


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, new_url):
        return None


def execute_action(ec2, instance_id, action, dry_run=False):
    if dry_run or action == "none":
        return False
    if action == "start":
        ec2.start_instances(InstanceIds=[instance_id])
    elif action == "stop":
        ec2.stop_instances(InstanceIds=[instance_id])
    else:
        raise ValueError("Unknown lifecycle action")
    return True


def jenkins_json(path, user, token):
    request = Request("http://127.0.0.1:8080" + path)
    credentials = base64.b64encode(f"{user}:{token}".encode()).decode()
    request.add_header("Authorization", "Basic " + credentials)
    with build_opener(NoRedirect()).open(request, timeout=10) as response:
        return json.load(response)


def run(dry_run=False):
    import boto3
    from botocore.config import Config
    region = os.environ["AWS_REGION"]
    instance_id = os.environ["AGENT_INSTANCE_ID"]
    credentials_path = Path(os.environ["JENKINS_READ_CREDENTIALS_FILE"])
    state_path = Path(os.environ["LIFECYCLE_STATE_FILE"])
    now = int(time.time())
    deadline = datetime.strptime(os.environ["PILOT_STOP_AT_UTC"], "%Y-%m-%dT%H:%M:%S").replace(tzinfo=timezone.utc).timestamp()
    ec2 = boto3.client(
        "ec2",
        region_name=region,
        config=Config(connect_timeout=5, read_timeout=10, retries={"total_max_attempts": 2, "mode": "standard"}),
    )
    instance = ec2.describe_instances(InstanceIds=[instance_id])["Reservations"][0]["Instances"][0]
    instance_state = instance["State"]["Name"]
    if now >= deadline:
        execute_action(ec2, instance_id, decide_action(instance_state, False, False, now, now, expired=True), dry_run=dry_run)
        print("Pilot expired; the agent will not be started.")
        return

    if credentials_path.stat().st_mode & 0o077:
        raise ValueError("The lifecycle credential file must not be accessible to group or others")
    credentials = json.loads(credentials_path.read_text())
    if credentials.get("user") != "jenkins-lifecycle" or not isinstance(credentials.get("token"), str) or not credentials["token"]:
        raise ValueError("A credential for the approved read-only service identity is required")
    stored = json.loads(state_path.read_text()) if state_path.exists() else {}
    last_active = stored.get("last_active", now)

    # A failed Jenkins read must not be interpreted as an idle agent.
    queue = jenkins_json("/queue/api/json?tree=items[task[name,fullName,url]]", credentials["user"], credentials["token"])
    node = jenkins_json("/computer/android-aws/api/json?tree=offline,idle", credentials["user"], credentials["token"])
    job_prefix = os.environ.get("JENKINS_JOB_PREFIX", "solventa-android")
    queued, busy = read_demand(queue, node, job_prefix)

    if queued or busy:
        last_active = now
    action = decide_action(instance_state, queued, busy, now, last_active)
    if dry_run:
        print(json.dumps({"dry_run": True, "state": instance_state, "queued": queued, "busy": busy, "planned_action": action}))
        return
    execute_action(ec2, instance_id, action)
    if action == "start":
        last_active = now

    state_path.parent.mkdir(parents=True, exist_ok=True)
    state_path.write_text(json.dumps({"last_active": last_active}))
    os.chmod(state_path, 0o600)
    print(f"Agent lifecycle: state={instance_state}, action={action}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true", help="Inspect demand without changing instance state or the idle marker")
    args = parser.parse_args()
    try:
        run(dry_run=args.dry_run)
        return 0
    except Exception as error:
        print(f"Lifecycle check failed ({type(error).__name__}); no idle assumption was made.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
