"""Loopback-only fixture for ClaimStatusDeviceE2eTest; never uses deployed services."""

import argparse
import json
import os
import sys
from contextlib import ExitStack
from datetime import UTC, datetime
from pathlib import Path
from tempfile import TemporaryDirectory
from threading import Event, Thread


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--backend-dir", type=Path, required=True)
    parser.add_argument("--fixture", type=Path, required=True)
    args = parser.parse_args()
    root = args.backend_dir.resolve()
    sys.path[:0] = [str(root / "tests")] + [
        str(root / "services" / service / "src")
        for service in ("bff-movil", "bff-web", "identidad", "polizas", "siniestros")
    ]
    from claims_local_stack import QuietHandler, call, stack
    from flask import Flask, Response, request
    from siniestros.infrastructure.models import CambioEstadoModel
    from siniestros.models import Claim
    from sqlalchemy import event, func, select
    from sqlalchemy.engine import Engine
    from sqlalchemy.orm import Session
    from werkzeug.serving import make_server

    fixture = args.fixture.resolve()
    if fixture.exists():
        raise FileExistsError("La fixture ya existe; no se sobrescribe.")
    stopped = Event()
    with (
        TemporaryDirectory(prefix="solventa-status-") as directory,
        ExitStack() as resources,
    ):
        engines = set()

        def track(connection):
            engines.add(connection.engine)

        def close_engines():
            # Windows cannot remove SQLite files while a service pool still owns connections.
            for engine in engines:
                engine.dispose()

        event.listen(Engine, "engine_connect", track)
        resources.callback(event.remove, Engine, "engine_connect", track)
        resources.callback(close_engines)
        runtime = resources.enter_context(stack(Path(directory)))
        first, second = runtime["users"]
        status, initial = call(
            runtime["mobile"] + "/siniestros/" + first["claim"]["id"] + "/estado",
            token=first["token"],
        )
        assert status == 200 and initial["estado"] == "reportado"
        data = {
            "email": first["email"],
            "password": first["password"],
            "actor": first["id"],
            "otherEmail": second["email"],
            "otherPassword": second["password"],
            "otherActor": second["id"],
            "claimId": first["claim"]["id"],
            "otherClaimId": second["claim"]["id"],
            "updatedAt": initial["actualizadoEn"],
        }
        gateway = Flask(__name__)
        unavailable = False
        mutations = 0

        @gateway.get("/__ready")
        def ready():
            return {"ready": True}

        @gateway.post("/__control/<mode>")
        def control(mode: str):
            nonlocal unavailable
            if mode == "stop":
                stopped.set()
            elif mode in {"online", "unavailable"}:
                unavailable = mode == "unavailable"
            elif mode == "reviewing":
                now = datetime.now(UTC)
                engine = runtime["app"].extensions["siniestros_engine"]
                with Session(engine) as session, session.begin():
                    claim = session.get(Claim, first["claim"]["id"])
                    assert claim is not None
                    sequence = session.scalar(
                        select(func.max(CambioEstadoModel.secuencia)).where(
                            CambioEstadoModel.siniestro_id == claim.id
                        )
                    )
                    claim.estado = "en_revision"
                    claim.actualizado_en = now
                    session.add(
                        CambioEstadoModel(
                            siniestro_id=claim.id,
                            secuencia=sequence + 1,
                            estado="en_revision",
                            ocurrido_en=now,
                            visible_cliente=True,
                        )
                    )
                return {"actualizadoEn": now.isoformat()}
            else:
                return {"code": "control_desconocido"}, 400
            return {"mode": mode}

        @gateway.route("/movil/v1/<path:tail>", methods=["GET", "POST", "DELETE"])
        def forward(tail: str):
            nonlocal mutations
            if tail.startswith("siniestros") and request.method != "GET":
                mutations += 1
            if unavailable and tail.endswith("/estado"):
                return {"code": "servicio_no_disponible"}, 503
            status, body = call(
                runtime["mobile"] + "/" + tail,
                method=request.method,
                data=request.get_data() or None,
                headers={
                    name: request.headers[name]
                    for name in ("Authorization", "Content-Type", "Idempotency-Key")
                    if name in request.headers
                },
            )
            return Response(
                body if isinstance(body, bytes) else json.dumps(body),
                status=status,
                content_type="application/json",
                headers={"Cache-Control": "no-store"},
            )

        server = make_server(
            "127.0.0.1", 18721, gateway, threaded=True, request_handler=QuietHandler
        )
        with os.fdopen(
            os.open(fixture, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600), "w"
        ) as out:
            json.dump(data, out)
        thread = Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            print("READY http://127.0.0.1:18721", flush=True)
            stopped.wait()
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)
            fixture.unlink()
            print(json.dumps({"claim_mutations_from_device": mutations}), flush=True)
            assert mutations == 0, "La consulta no debe modificar siniestros."
    assert not Path(directory).exists()
    print("Temporary databases removed.", flush=True)


if __name__ == "__main__":
    main()
