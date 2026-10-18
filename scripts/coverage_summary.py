"""Summarize JaCoCo/Cobertura and current JUnit reports without external packages."""

import argparse
import glob
import xml.etree.ElementTree as ET
from pathlib import Path


def ratio(covered: int, total: int) -> str:
    if not 0 <= covered <= total:
        raise ValueError("Invalid coverage counters")
    return f"{100 * covered / total:.2f}%" if total else "—"


def counters(path: Path) -> tuple[tuple[int, int], tuple[int, int]]:
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
        ratio(covered, total)
        values.append((covered, total))
    return values[0], values[1]


def junit_counts(patterns: list[str]) -> tuple[int, int, int, list[str]]:
    cases: list[ET.Element] = []
    seen: set[str] = set()
    problems = []
    for pattern in patterns:
        paths = glob.glob(pattern, recursive=True)
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
    skipped = sum(c.find("skipped") is not None and c.find("failure") is None and c.find("error") is None for c in cases)
    return len(cases) - failed - skipped, failed, skipped, problems


def summarize(sources: list[str], tests: list[str], scope: str) -> tuple[str, bool]:
    rows = []
    problems = []
    line_results = []
    for source in sources:
        label, filename = source.split("=", 1)
        try:
            lines, branches = counters(Path(filename))
            line_results.append(ratio(*lines))
            for metric, (covered, total) in (("Líneas", lines), ("Ramas", branches)):
                if not total:
                    progress = "⚪ No aplica"
                elif covered * 100 >= total * 80:
                    progress = "🟢 Objetivo alcanzado"
                else:
                    difference = 80 - 100 * covered / total
                    distance = "< 0.01" if difference < 0.01 else f"{difference:.2f}"
                    progress = f"🟠 A {distance} pp del objetivo"
                rows.append(f"| {label} | {metric} | **{ratio(covered, total)}** | {covered} / {total} | {total - covered} | {progress} |")
        except (OSError, ET.ParseError, KeyError, ValueError):
            rows.append(f"| {label} | — | No disponible | — | — | 🔴 Revisar informe |")
            problems.append(f"Reporte ausente o inválido: {label}.")

    passed, failed, skipped, junit_problems = junit_counts(tests)
    problems.extend(junit_problems)
    title = "Cobertura incompleta" if problems else "Cobertura JVM"
    if len(line_results) == 1 and not problems:
        title += f": {line_results[0]} en líneas"
    summary = [
        title,
        "",
        "> 🎯 **Objetivo recomendado: 80 % en líneas y ramas.** Referencia de mejora; no bloquea la integración.",
        "",
        "| Módulo | Métrica | Cobertura | Cubiertas / total | Pendientes | Frente al objetivo |",
        "| --- | --- | ---: | ---: | ---: | --- |",
        *rows,
        "",
        "### Pruebas JVM",
        "",
        "| ✅ Correctas | ❌ Fallidas | ⏭️ Omitidas | Total |",
        "| ---: | ---: | ---: | ---: |",
        f"| {passed} | {failed} | {skipped} | {passed + failed + skipped} |",
        "",
        f"**Alcance:** {scope}",
        "",
        "Los indicadores comparan cada métrica con el objetivo. **pp** = puntos porcentuales.",
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
