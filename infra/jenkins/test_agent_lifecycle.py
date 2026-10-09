import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("agent_lifecycle", Path(__file__).with_name("agent-lifecycle.py"))
lifecycle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lifecycle)


class AgentLifecycleTest(unittest.TestCase):
    def test_multibranch_short_name_uses_its_job_url(self):
        task = {"name": "PR-2", "url": "http://localhost:8080/job/solventa-android/job/PR-2/"}
        self.assertTrue(lifecycle.belongs_to_job(task, "solventa-android"))

    def test_multibranch_full_name_matches(self):
        self.assertTrue(lifecycle.belongs_to_job({"fullName": "solventa-android/develop"}, "solventa-android"))

    def test_similarly_named_job_does_not_match(self):
        task = {"name": "solventa-android-other", "fullName": "solventa-android-other/develop", "url": "http://localhost/job/solventa-android-other/job/develop/"}
        self.assertFalse(lifecycle.belongs_to_job(task, "solventa-android"))

    def test_missing_job_metadata_does_not_match(self):
        self.assertFalse(lifecycle.belongs_to_job({}, "solventa-android"))

    def test_queued_work_starts_a_stopped_agent(self):
        self.assertEqual("start", lifecycle.decide_action("stopped", True, False, 1000, 0))

    def test_no_work_keeps_agent_stopped(self):
        self.assertEqual("none", lifecycle.decide_action("stopped", False, False, 1000, 0))

    def test_pending_agent_is_not_started_twice(self):
        self.assertEqual("none", lifecycle.decide_action("pending", True, False, 1000, 0))

    def test_busy_agent_is_not_stopped(self):
        self.assertEqual("none", lifecycle.decide_action("running", False, True, 1000, 0))

    def test_queue_prevents_idle_shutdown(self):
        self.assertEqual("none", lifecycle.decide_action("running", True, False, 1000, 0))

    def test_five_minutes_idle_stops_agent(self):
        self.assertEqual("stop", lifecycle.decide_action("running", False, False, 1000, 700))

    def test_short_idle_period_does_not_stop_agent(self):
        self.assertEqual("none", lifecycle.decide_action("running", False, False, 1000, 701))

    def test_stopping_agent_is_left_alone(self):
        self.assertEqual("none", lifecycle.decide_action("stopping", True, False, 1000, 0))

    def test_expiration_stops_agent_even_with_queued_work(self):
        self.assertEqual("stop", lifecycle.decide_action("running", True, True, 1000, 0, expired=True))

    def test_expiration_does_not_restart_agent(self):
        self.assertEqual("none", lifecycle.decide_action("stopped", True, False, 1000, 0, expired=True))


if __name__ == "__main__":
    unittest.main()
