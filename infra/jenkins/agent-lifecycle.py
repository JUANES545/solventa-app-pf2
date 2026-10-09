#!/usr/bin/env python3
"""Start the fixed Android agent for queued work and stop it after inactivity."""

import base64
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import time
from urllib.request import Request, urlopen
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
    return urlsplit(task.get("url", "")).path.startswith("/job/" + quote(job_name, safe="") + "/")


def jenkins_json(path, user, token):
    request = Request("http://127.0.0.1:8080" + path)
    credentials = base64.b64encode(f"{user}:{token}".encode()).decode()
    request.add_header("Authorization", "Basic " + credentials)
    with urlopen(request, timeout=10) as response:
        return json.load(response)


def main():
    import boto3

    region = os.environ["AWS_REGION"]
    instance_id = os.environ["AGENT_INSTANCE_ID"]
    credentials_path = Path(os.environ["JENKINS_READ_CREDENTIALS_FILE"])
    state_path = Path(os.environ["LIFECYCLE_STATE_FILE"])
    now = int(time.time())
    deadline = datetime.strptime(os.environ["PILOT_STOP_AT_UTC"], "%Y-%m-%dT%H:%M:%S").replace(tzinfo=timezone.utc).timestamp()
    ec2 = boto3.client("ec2", region_name=region)
    instance = ec2.describe_instances(InstanceIds=[instance_id])["Reservations"][0]["Instances"][0]
    instance_state = instance["State"]["Name"]
    if now >= deadline:
        if decide_action(instance_state, False, False, now, now, expired=True) == "stop":
            ec2.stop_instances(InstanceIds=[instance_id])
        print("Pilot expired; the agent will not be started.")
        return

    credentials = json.loads(credentials_path.read_text())
    stored = json.loads(state_path.read_text()) if state_path.exists() else {}
    last_active = stored.get("last_active", now)

    # A failed Jenkins read must not be interpreted as an idle agent.
    queue = jenkins_json("/queue/api/json?tree=items[task[name,fullName,url]]", credentials["user"], credentials["token"])
    node = jenkins_json("/computer/android-aws/api/json?tree=offline,idle", credentials["user"], credentials["token"])
    job_prefix = os.environ.get("JENKINS_JOB_PREFIX", "solventa-android")
    queued = any(belongs_to_job(item.get("task", {}), job_prefix) for item in queue.get("items", []))
    busy = not node.get("offline", True) and not node.get("idle", False)

    if queued or busy:
        last_active = now
    action = decide_action(instance_state, queued, busy, now, last_active)
    if action == "start":
        ec2.start_instances(InstanceIds=[instance_id])
        last_active = now
    elif action == "stop":
        ec2.stop_instances(InstanceIds=[instance_id])

    state_path.parent.mkdir(parents=True, exist_ok=True)
    state_path.write_text(json.dumps({"last_active": last_active}))
    os.chmod(state_path, 0o600)
    print(f"Agent lifecycle: state={instance_state}, action={action}")


if __name__ == "__main__":
    try:
        main()
    except Exception:
        raise SystemExit("Lifecycle check failed; no idle assumption was made.")
