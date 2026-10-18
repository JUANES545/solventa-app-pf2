import tempfile
import unittest
from pathlib import Path

from ci_summary import summarize


class CiSummaryTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def write(self, name: str, contents: str) -> None:
        file = self.root / name
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(contents, encoding="utf-8")

    def summary(self, kind: str, steps: dict) -> str:
        return summarize(kind, steps, self.root, "a" * 40, "https://github.com/owner/repo/actions/runs/1")

    def test_verification_shows_actual_outcomes_junit_and_lint(self) -> None:
        self.write("app/build/test-results/testDebugUnitTest/TEST.xml",
                   '<testsuite><testcase/><testcase><failure/></testcase><testcase><skipped/></testcase></testsuite>')
        self.write("app/build/reports/lint-results-debug.xml",
                   '<issues><issue severity="Error"/><issue severity="Warning"/></issues>')
        result = self.summary("verification", {"android": {"outcome": "failure"}, "package": {"outcome": "skipped"}})
        self.assertIn("| Unitarias, lint y compilación debug | ❌ Falló |", result)
        self.assertIn("| Compilación de Solventa | ⏭️ Omitido |", result)
        self.assertIn("| 1 | 1 | 1 | 3 |", result)
        self.assertIn("**Errores:** 1 · **Advertencias:** 1", result)
        self.assertIn("a" * 40, result)

    def test_missing_reports_are_not_presented_as_success(self) -> None:
        result = self.summary("verification", {})
        self.assertIn("Informe JUnit ausente o incompleto", result)
        self.assertIn("Informe de lint no disponible", result)
        self.assertNotIn("| 0 | 0 | 0 | 0 |", result)

    def test_instrumentation_uses_only_device_reports(self) -> None:
        self.write("app/build/outputs/androidTest-results/connected/device/TEST.xml", '<testsuite><testcase/></testsuite>')
        self.write("app/build/test-results/testDebugUnitTest/TEST.xml", '<testsuite><testcase/><testcase/></testsuite>')
        result = self.summary("instrumentation", {"device": {"outcome": "success"}})
        self.assertIn("| Pruebas UI · Android 35 | ✅ Correcto |", result)
        self.assertIn("| 1 | 0 | 0 | 1 |", result)
        self.assertNotIn("### Android Lint", result)

    def test_verified_backport_reports_reuse_without_fabricating_test_counts(self) -> None:
        result = self.summary("verification", {"policy": {"outputs": {"skip_tests": "true"}}})
        self.assertIn("Evidencia de la release verificada", result)
        self.assertNotIn("### Pruebas ejecutadas", result)
        self.assertNotIn("✅ Correcto", result)
        self.assertIn("Revisión de la política", result)
        self.assertNotIn("Revisión validada", result)

    def test_malformed_report_does_not_hide_step_failure(self) -> None:
        self.write("app/build/outputs/androidTest-results/connected/device/TEST.xml", '<broken')
        result = self.summary("instrumentation", {"device": {"outcome": "failure"}})
        self.assertIn("❌ Falló", result)
        self.assertIn("Informe JUnit ausente o incompleto", result)

    def test_unknown_lint_severity_is_not_reported_as_zero_errors(self) -> None:
        self.write("app/build/reports/lint-results-debug.xml", '<issues><issue/></issues>')
        result = self.summary("verification", {})
        self.assertIn("Informe de lint no disponible", result)
        self.assertNotIn("**Errores:** 0", result)


if __name__ == "__main__":
    unittest.main()
