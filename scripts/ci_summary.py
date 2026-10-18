"""Write a GitHub Actions summary using step outcomes and generated Android reports."""

import argparse
import json
import os
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

from coverage_summary import junit_counts


def summarize(kind: str, steps: dict, root: Path, revision: str, run_url: str) -> str:
    title = "Validación Android" if kind == "verification" else "Pruebas de interfaz Android"
    lines = [f"## {title}", ""]
    reused = steps.get("policy", {}).get("outputs", {}).get("skip_tests") == "true"
    if reused:
        lines += ["♻️ **Evidencia de la release verificada.** No se repiten compilación ni pruebas.", ""]
    else:
        checks = [("automation", "Scripts de automatización"), ("version", "Versión"),
                  ("android", "Unitarias, lint y compilación debug"), ("publication", "Política de publicación"),
                  ("package", "Compilación de Solventa"), ("apk", "APK disponible"), ("reports", "Informes disponibles")]
        if kind == "instrumentation":
            checks = [("device", "Pruebas UI · Android 35"), ("reports", "Informes disponibles")]
        states = {"success": "✅ Correcto", "failure": "❌ Falló", "skipped": "⏭️ Omitido", "cancelled": "⏹️ Cancelado"}
        lines += ["| Comprobación | Resultado |", "| --- | --- |"]
        for step, label in checks:
            lines.append(f"| {label} | {states.get(steps.get(step, {}).get('outcome'), '⚪ Sin resultado')} |")
        pattern = "app/build/test-results/testDebugUnitTest/*.xml"
        if kind == "instrumentation":
            pattern = "app/build/outputs/androidTest-results/connected/**/*.xml"
        passed, failed, skipped, problems = junit_counts([str(root / pattern)])
        lines += ["", "### Pruebas ejecutadas", ""]
        if problems:
            lines.append("⚠️ Informe JUnit ausente o incompleto; no se presenta como una ejecución sin fallos.")
        else:
            lines += ["| ✅ Correctas | ❌ Fallidas | ⏭️ Omitidas | Total |", "| ---: | ---: | ---: | ---: |",
                      f"| {passed} | {failed} | {skipped} | {passed + failed + skipped} |"]
        if kind == "verification":
            lines += ["", "### Android Lint", ""]
            try:
                lint = ET.parse(root / "app/build/reports/lint-results-debug.xml").getroot()
                if lint.tag != "issues":
                    raise ValueError("Unknown lint format")
                issues = list(lint.iter("issue"))
                if any(issue.get("severity", "").lower() not in ("fatal", "error", "warning", "information", "ignore") for issue in issues):
                    raise ValueError("Unknown lint severity")
                errors = sum(issue.get("severity", "").lower() in ("error", "fatal") for issue in issues)
                warnings = sum(issue.get("severity", "").lower() == "warning" for issue in issues)
                lines.append(f"**Errores:** {errors} · **Advertencias:** {warnings}")
            except (OSError, ET.ParseError, ValueError):
                lines.append("⚪ Informe de lint no disponible. Consulta la etapa de validación.")
    revision_label = "Revisión de la política" if reused else "Revisión validada"
    lines += ["", f"[Ver ejecución e informes]({run_url})", "",
              f"**{revision_label}:** `{revision}`."]
    return "\n".join(lines) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("kind", choices=["verification", "instrumentation"])
    args = parser.parse_args()
    steps = json.loads(os.environ["CI_STEPS"])
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    run_url = f"{os.environ['GITHUB_SERVER_URL']}/{os.environ['GITHUB_REPOSITORY']}/actions/runs/{os.environ['GITHUB_RUN_ID']}"
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as output:
        output.write(summarize(args.kind, steps, Path("."), revision, run_url))


if __name__ == "__main__":
    main()
