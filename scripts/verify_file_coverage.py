"""Exige mas del 80 % de lineas y ramas en cada archivo fuente de la app."""

import argparse
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def verify(report: Path, source_roots: list[Path]) -> tuple[list[str], list[str]]:
    root = ET.parse(report).getroot()
    if root.tag != "report":
        raise ValueError("Se requiere un informe XML JaCoCo.")
    sources = {
        path.relative_to(directory).as_posix()
        for directory in source_roots
        for path in directory.rglob("*")
        if path.is_file() and path.suffix in {".kt", ".java"}
    }
    if not sources:
        raise ValueError("No se encontraron fuentes de la app.")
    measured = {}
    for package in root.findall("package"):
        for source in package.findall("sourcefile"):
            name = "/".join(filter(None, [package.get("name"), source.get("name")]))
            if name in measured:
                raise ValueError(f"Archivo duplicado en el informe: {name}")
            measured[name] = source
    rows = ["Archivo | Lineas | Ramas", "--- | --- | ---"]
    failures = []
    for name in sorted(sources):
        source = measured.get(name)
        if source is None:
            failures.append(f"{name}: ausente del informe")
            rows.append(f"{name} | AUSENTE | AUSENTE")
            continue
        counters = {counter.get("type"): counter for counter in source.findall("counter")}
        if "LINE" not in counters:
            raise ValueError(f"{name}: faltan contadores de lineas")
        values = []
        for metric in ("LINE", "BRANCH"):
            counter = counters.get(metric)
            if counter is not None and not {"covered", "missed"} <= counter.attrib.keys():
                raise ValueError(f"{name}: contador {metric} incompleto")
            covered = int(counter.attrib["covered"]) if counter is not None else 0
            missed = int(counter.attrib["missed"]) if counter is not None else 0
            if covered < 0 or missed < 0:
                raise ValueError(f"{name}: contadores negativos")
            total = covered + missed
            if total == 0:
                values.append("N/A (sin elementos medibles)")
            else:
                values.append(f"{100 * covered / total:.2f}% ({covered}/{total})")
                if covered * 100 <= total * 80:
                    failures.append(f"{name}: {metric} debe superar 80% ({covered}/{total})")
        rows.append(" | ".join([name, *values]))
    return rows, failures


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path, default=Path("app/build/reports/coverage/unit/coverage.xml"))
    parser.add_argument("--sources", type=Path, nargs="+", default=[Path("app/src/main/java"), Path("app/src/main/kotlin")])
    args = parser.parse_args()
    try:
        rows, failures = verify(args.report, args.sources)
    except (OSError, ValueError, ET.ParseError) as error:
        print(f"No se pudo verificar la cobertura: {error}", file=sys.stderr)
        return 2
    print("\n".join(rows))
    if failures:
        print("\n".join(failures), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
