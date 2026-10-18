import tempfile
import unittest
from pathlib import Path

from coverage_summary import counters, summarize, junit_counts


class CoverageSummaryTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def write(self, name: str, text: str) -> Path:
        path = self.root / name
        path.write_text(text, encoding="utf-8")
        return path

    def test_jacoco(self) -> None:
        path = self.write("coverage.xml", '<report><counter type="LINE" covered="3" '
                          'missed="1"/><counter type="BRANCH" covered="1" missed="1"/></report>')
        self.assertEqual(counters(path), ((3, 4), (1, 2)))

    def test_cobertura(self) -> None:
        path = self.write("coverage.xml", '<coverage lines-covered="2" lines-valid="4" '
                          'branches-covered="0" branches-valid="0"/>')
        self.assertEqual(counters(path), ((2, 4), (0, 0)))

    def test_missing_report_is_partial_not_zero(self) -> None:
        summary, complete = summarize([f"App={self.root / 'missing.xml'}"],
                                      [str(self.root / "tests.xml")], "unitarias")
        self.assertFalse(complete)
        self.assertIn("No disponible", summary)
        self.assertIn("Reporte parcial", summary)

    def test_invalid_counters_are_rejected(self) -> None:
        for covered, total in ((0, 0), (5, 4), (-1, 4)):
            path = self.write("coverage.xml", f'<coverage lines-covered="{covered}" '
                              f'lines-valid="{total}" branches-covered="0" branches-valid="0"/>')
            with self.assertRaises(ValueError):
                counters(path)

    def test_only_selected_junit_files_are_counted(self) -> None:
        coverage = self.write("coverage.xml", '<coverage lines-covered="2" lines-valid="4" '
                              'branches-covered="0" branches-valid="0"/>')
        tests = self.write("current.xml", '<testsuite tests="99"><testcase/>'
                           '<testcase><failure/></testcase><testcase><skipped/></testcase></testsuite>')
        self.write("old.xml", '<testsuite><testcase/></testsuite>')
        summary, complete = summarize([f"App={coverage}"], [str(tests), str(tests)], "JVM")
        self.assertTrue(complete)
        self.assertIn("| 1 | 1 | 1 | 3 |", summary)

    def test_malformed_xml_is_partial(self) -> None:
        path = self.write("bad.xml", "<broken")
        summary, complete = summarize([f"App={path}"], [str(path)], "unitarias")
        self.assertFalse(complete)
        self.assertIn("Cobertura incompleta", summary)

    def test_recommendation_and_distance_do_not_fail_a_complete_report(self) -> None:
        coverage = self.write("coverage.xml", '<coverage lines-covered="3" lines-valid="4" '
                              'branches-covered="0" branches-valid="0"/>')
        tests = self.write("tests.xml", '<testsuite><testcase/></testsuite>')
        summary, complete = summarize([f"App={coverage}"], [str(tests)], "JVM")
        self.assertTrue(complete)
        self.assertIn("Objetivo recomendado: 80 %", summary)
        self.assertIn("**75.00%** | 3 / 4 | 1 | 🟠 A 5.00 pp", summary)
        self.assertIn("⚪ No aplica", summary)
        self.assertNotIn("Sin umbral", summary)

    def test_exact_target_is_green_and_not_decided_by_rounded_display(self) -> None:
        tests = self.write("tests.xml", '<testsuite><testcase/></testsuite>')
        for covered, total, expected in ((4, 5, "🟢 Objetivo alcanzado"), (79999, 100000, "🟠")):
            coverage = self.write("coverage.xml", f'<coverage lines-covered="{covered}" lines-valid="{total}" '
                                  'branches-covered="0" branches-valid="0"/>')
            summary, complete = summarize([f"App={coverage}"], [str(tests)], "JVM")
            self.assertTrue(complete)
            self.assertIn(expected, summary)

    def test_failed_case_is_not_counted_twice_as_skipped(self) -> None:
        tests = self.write("tests.xml", '<testsuite><testcase><error/><skipped/></testcase></testsuite>')
        self.assertEqual(junit_counts([str(tests)]), (0, 1, 0, []))


if __name__ == "__main__":
    unittest.main()
