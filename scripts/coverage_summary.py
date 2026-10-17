"""Summarize JaCoCo/Cobertura and current JUnit reports without external packages."""

import argparse
import glob
import xml.etree.ElementTree as ET
from pathlib import Path


def ratio(covered: int, total: int) -> str:
    if not 0 <= covered <= total:
        raise ValueError("Invalid coverage counters")
    return f"{100 * covered / total:.2f}% ({covered}/{total})" if total else "—"


def counters(path: Path) -> tuple[str, str]:
    root = ET.parse(path).getroot()
    values = []
    for kind, metric in (("LINE", "lines"), ("BRANCH", "branches")):
        if root.tag == "report":
            counter = root.find(f"counter[@type='{kind}']")
            if counter is None:
                raise ValueError("Missing JaCoCo counter")
            covered = int(counter.attrib["covered"])
            total = covered + int(counter.attrib["missed"])
        elif root.tag == "coverage":
            covered = int(root.attrib[f"{metric}-covered"])
            total = int(root.attrib[f"{metric}-valid"])
        else:
            raise ValueError("Unknown coverage format")
        if kind == "LINE" and total == 0:
            raise ValueError("No application lines measured")
        values.append(ratio(covered, total))
    return values[0], values[1]


def summarize(sources: list[str], tests: list[str], scope: str) -> tuple[str, bool]:
    rows = []
    problems = []
    for source in sources:
        label, filename = source.split("=", 1)
        try:
            lines, branches = counters(Path(filename))
            rows.append(f"| {label} | {lines} | {branches} |")
        except (OSError, ET.ParseError, KeyError, ValueError):
            rows.append(f"| {label} | No disponible | No disponible |")
            problems.append(f"Reporte ausente o inválido: {label}.")

    cases: list[ET.Element] = []
    seen: set[str] = set()
    for pattern in tests:
        paths = glob.glob(pattern)
        if not paths:
            problems.append(f"JUnit ausente: `{pattern}`.")
        for filename in paths:
            if filename in seen:
                continue
            seen.add(filename)
            try:
                current = list(ET.parse(filename).getroot().iter("testcase"))
                if not current:
                    raise ValueError("Empty JUnit")
                cases.extend(current)
            except (OSError, ET.ParseError, ValueError):
                problems.append(f"JUnit inválido: `{filename}`.")
    failed = sum(c.find("failure") is not None or c.find("error") is not None for c in cases)
    skipped = sum(c.find("skipped") is not None for c in cases)
    title = "Cobertura incompleta" if problems else "Cobertura generada"
    if len(rows) == 1 and not problems:
        title += f": líneas {rows[0].split('|')[2].strip()}"
    summary = [
        title,
        "",
        f"**Alcance:** {scope}",
        "",
        "| Módulo | Líneas | Ramas |",
        "| --- | --- | --- |",
        *rows,
        "",
        f"**Pruebas:** {len(cases) - failed - skipped} correctas, {failed} fallidas, "
        f"{skipped} omitidas ({len(cases)} casos JUnit).",
        "",
        "Sin umbral mínimo. Un reporte generado no certifica calidad funcional ni despliegue.",
    ]
    if problems:
        summary.extend(["", "**Reporte parcial; no usar como cobertura completa.**", *problems])
    return "\n".join(summary) + "\n", not problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--coverage", action="append", required=True)
    parser.add_argument("--tests", action="append", required=True)
    parser.add_argument("--scope", required=True)
    args = parser.parse_args()
    summary, complete = summarize(args.coverage, args.tests, args.scope)
    Path("reports").mkdir(exist_ok=True)
    Path("reports/coverage-summary.md").write_text(summary, encoding="utf-8")
    print(summary)
    return 0 if complete else 1


if __name__ == "__main__":
    raise SystemExit(main())
