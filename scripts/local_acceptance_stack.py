"""Owned loopback stack for LocalBackendAcceptanceTest; never connects to AWS."""

import argparse
import json
import os
import sys
from contextlib import ExitStack
from dataclasses import replace
from pathlib import Path
from tempfile import TemporaryDirectory
from threading import Event, Thread

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--backend-dir", type=Path, required=True)
parser.add_argument("--fixture", type=Path, required=True)
args = parser.parse_args()
root = args.backend_dir.resolve()
sys.path[:0] = [str(root / "tests")] + [
    str(root / "services" / name / "src")
    for name in ("bff-movil", "bff-web", "identidad", "polizas", "siniestros", "asistencias", "notificaciones")
]
from claims_local_stack import QuietHandler, call, migrate, serve, stack
from asistencias.app import create_app as assistance_app
from asistencias.domain import Config as AssistanceConfig
from notificaciones.app import create_app as notifications_app
from flask import Flask, Response, request
from sqlalchemy import event
from sqlalchemy.engine import Engine
from werkzeug.serving import make_server

fixture = args.fixture.resolve()
assert not fixture.exists()
engines = set()


def track(connection):
    engines.add(connection.engine)


event.listen(Engine, "engine_connect", track)
stop = Event()
with TemporaryDirectory(prefix="solventa-android-integration-") as directory, ExitStack() as resources:
    resources.callback(lambda: [engine.dispose() for engine in engines])
    path = Path(directory)
    runtime = resources.enter_context(stack(path))
    for name in ("asistencias", "notificaciones"):
        migrate(name, f"sqlite:///{path / (name + '.db')}")
    assistance = assistance_app(AssistanceConfig(
        database_url=f"sqlite:///{path / 'asistencias.db'}",
        internal_token=runtime["internal"], policies_url=runtime["policies"], claims_url=runtime["claims"],
    ))
    notifications = notifications_app(f"sqlite:///{path / 'notificaciones.db'}", runtime["internal"])
    assistance_url = resources.enter_context(serve(assistance))
    notifications_url = resources.enter_context(serve(notifications))
    mobile = runtime["mobileApp"]
    mobile.extensions["bff_config"] = replace(
        mobile.extensions["bff_config"],
        assistance_service_url=assistance_url,
        notifications_service_url=notifications_url,
    )
    gateway = Flask(__name__)
    state = {"offline": False, "drop-upload-ack": False}

    @gateway.get("/__ready")
    def ready():
        return {"ready": True}

    @gateway.post("/__control/<mode>")
    def control(mode):
        if mode == "stop":
            stop.set()
        elif mode == "offline":
            state["offline"] = True
        elif mode == "online":
            state["offline"] = False
            state["drop-upload-ack"] = False
        elif mode == "drop-upload-ack":
            state[mode] = True
        else:
            return {"code": "control_desconocido"}, 400
        return {"mode": mode}

    @gateway.route("/movil/v1/<path:tail>", methods=["GET", "POST", "PUT", "DELETE"])
    def forward(tail):
        if state["offline"]:
            return {"code": "servicio_no_disponible"}, 503
        status, body = call(
            runtime["mobile"] + "/" + tail, method=request.method,
            data=request.get_data() or None,
            headers={key: request.headers[key] for key in ("Authorization", "Content-Type", "Idempotency-Key", "Upload-Offset") if key in request.headers},
        )
        if state["drop-upload-ack"] and tail.endswith("/contenido") and request.method == "PUT":
            state["drop-upload-ack"] = False
            assert status == 200
            return {"code": "servicio_no_disponible"}, 503
        return Response(body if isinstance(body, bytes) else json.dumps(body),
                        status=status, content_type="application/json")

    user = runtime["users"][0]
    other = runtime["users"][1]
    server = make_server("127.0.0.1", 18723, gateway, threaded=True, request_handler=QuietHandler)
    resources.callback(server.server_close)
    descriptor = os.open(fixture, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    resources.callback(fixture.unlink)
    with os.fdopen(descriptor, "w") as out:
        json.dump({
            "base": "http://127.0.0.1:18723/movil/v1",
            "email": user["email"], "password": user["password"], "actor": user["id"],
            "otherEmail": other["email"], "otherPassword": other["password"], "otherActor": other["id"],
        }, out)
    thread = Thread(target=server.serve_forever, daemon=True)
    thread.start()
    resources.callback(thread.join, timeout=5)
    resources.callback(server.shutdown)
    print("READY local real services: Identity, Policies, Claims, Assistance, Notifications, mobile BFF", flush=True)
    stop.wait()
event.remove(Engine, "engine_connect", track)
assert not Path(directory).exists()
print("Private fixture and all temporary SQL databases removed.", flush=True)
