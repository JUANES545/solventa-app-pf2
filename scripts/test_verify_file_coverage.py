from pathlib import Path
import tempfile
import unittest

from verify_file_coverage import verify


class FileCoverageTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.sources = self.root / "source"
        (self.sources / "example").mkdir(parents=True)
        (self.sources / "example/App.kt").write_text("package example\nclass App", encoding="utf-8")
        self.report = self.root / "coverage.xml"

    def write(self, counters):
        self.report.write_text(
            '<report><package name="example"><sourcefile name="App.kt">'
            + counters + "</sourcefile></package></report>", encoding="utf-8")

    def test_exactly_eighty_is_not_above_eighty(self):
        self.write('<counter type="LINE" covered="80" missed="20"/>'
                   '<counter type="BRANCH" covered="81" missed="19"/>')
        _, failures = verify(self.report, [self.sources])
        self.assertEqual(1, len(failures))
        self.assertIn("LINE", failures[0])

    def test_global_average_cannot_hide_a_file_or_low_branch_coverage(self):
        (self.sources / "example/Other.kt").write_text("package example\nclass Other", encoding="utf-8")
        self.write('<counter type="LINE" covered="999" missed="1"/>'
                   '<counter type="BRANCH" covered="8" missed="2"/>')
        _, failures = verify(self.report, [self.sources])
        self.assertEqual(2, len(failures))
        self.assertTrue(any("BRANCH" in failure for failure in failures))
        self.assertTrue(any("Other.kt: ausente" in failure for failure in failures))

    def test_no_branches_is_not_a_fabricated_hundred_percent(self):
        self.write('<counter type="LINE" covered="1" missed="0"/>')
        rows, failures = verify(self.report, [self.sources])
        self.assertFalse(failures)
        self.assertIn("N/A", rows[-1])

    def test_strict_comparison_does_not_depend_on_rounded_percentage(self):
        self.write('<counter type="LINE" covered="80001" missed="19999"/>'
                   '<counter type="BRANCH" covered="9" missed="1"/>')
        self.assertFalse(verify(self.report, [self.sources])[1])

    def test_negative_counts_and_missing_sources_fail(self):
        self.write('<counter type="LINE" covered="-1" missed="2"/>')
        with self.assertRaises(ValueError):
            verify(self.report, [self.sources])
        with self.assertRaises(ValueError):
            verify(self.report, [self.root / "missing"])

    def test_incomplete_report_is_not_a_successful_empty_measurement(self):
        self.write("")
        with self.assertRaises(ValueError):
            verify(self.report, [self.sources])
        self.write('<counter type="LINE"/>')
        with self.assertRaises(ValueError):
            verify(self.report, [self.sources])


if __name__ == "__main__":
    unittest.main()
