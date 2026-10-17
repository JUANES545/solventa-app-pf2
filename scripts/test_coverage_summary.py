import tempfile
import unittest
from pathlib import Path

from coverage_summary import counters, summarize


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
        self.assertEqual(counters(path), ("75.00% (3/4)", "50.00% (1/2)"))

    def test_cobertura(self) -> None:
        path = self.write("coverage.xml", '<coverage lines-covered="2" lines-valid="4" '
                          'branches-covered="0" branches-valid="0"/>')
        self.assertEqual(counters(path), ("50.00% (2/4)", "—"))

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
        self.assertIn("1 correctas, 1 fallidas, 1 omitidas (3 casos JUnit)", summary)

    def test_malformed_xml_is_partial(self) -> None:
        path = self.write("bad.xml", "<broken")
        summary, complete = summarize([f"App={path}"], [str(path)], "unitarias")
        self.assertFalse(complete)
        self.assertIn("Cobertura incompleta", summary)


if __name__ == "__main__":
    unittest.main()
