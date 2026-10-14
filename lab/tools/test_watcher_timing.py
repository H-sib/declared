"""Unit tests for the pure functions in watcher_timing.py. Run: python tools/test_watcher_timing.py"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import watcher_timing as wt  # noqa: E402

LOG = ("1000\twatcher started\n"
       "2000\tsetting-changed secure:enabled_accessibility_services\n"
       "2500\tpackage PACKAGE_ADDED lab.fixture.a11y\n"
       "garbage line without a tab\n"
       "x\tnot a number\n"
       "3100\tnetwork lost\n")


class ParseTests(unittest.TestCase):
    def test_parses_valid_lines_and_skips_garbage(self):
        ev = wt.parse_watch_log(LOG)
        self.assertEqual([t for t, _ in ev], [1000, 2000, 2500, 3100])

    def test_empty_and_none(self):
        self.assertEqual(wt.parse_watch_log(""), [])
        self.assertEqual(wt.parse_watch_log(None), [])


class MatchTests(unittest.TestCase):
    ev = wt.parse_watch_log(LOG)

    def test_first_match_after_t0(self):
        ts, text = wt.first_match(self.ev, r"PACKAGE_ADDED lab\.fixture\.a11y", 2000)
        self.assertEqual(ts, 2500)

    def test_ignores_events_before_t0(self):
        self.assertIsNone(wt.first_match(self.ev, r"enabled_accessibility_services", 2500))

    def test_clock_slack_allows_tiny_negative_offset(self):
        self.assertIsNotNone(wt.first_match(self.ev, r"enabled_accessibility_services", 2030))
        self.assertIsNone(wt.first_match(self.ev, r"enabled_accessibility_services", 2300))

    def test_no_match(self):
        self.assertIsNone(wt.first_match(self.ev, r"PACKAGE_REMOVED", 0))


class SummaryTests(unittest.TestCase):
    def rows(self):
        def r(cond, event, observed, lat):
            return {"condition": cond, "event": event, "observed": observed, "latency_ms_from_start": lat}
        return [r("front", "e1", True, 100), r("front", "e1", True, 300), r("front", "e1", True, 200),
                r("killed", "e1", False, ""), r("front", "e2", False, "")]

    def test_median_max_and_counts(self):
        s = {(x["condition"], x["event"]): x for x in wt.summarize(self.rows())}
        self.assertEqual(s[("front", "e1")]["observed"], 3)
        self.assertEqual(s[("front", "e1")]["median_ms"], 200)
        self.assertEqual(s[("front", "e1")]["max_ms"], 300)
        self.assertEqual(s[("killed", "e1")]["observed"], 0)
        self.assertIsNone(s[("killed", "e1")]["median_ms"])

    def test_markdown_marks_misses(self):
        md = wt.to_markdown(wt.summarize(self.rows()))
        self.assertIn("**missed** (0/1)", md)
        self.assertIn("3/3, median 200 ms, max 300 ms", md)

    def test_string_booleans_from_csv_are_handled(self):
        rows = [{"condition": "c", "event": "e", "observed": "True", "latency_ms_from_start": "50"},
                {"condition": "c", "event": "e", "observed": "False", "latency_ms_from_start": ""}]
        s = wt.summarize(rows)[0]
        self.assertEqual((s["observed"], s["reps"], s["median_ms"]), (1, 2, 50))


class SafetyTests(unittest.TestCase):
    def test_refuses_a_non_emulator(self):
        self.assertEqual(wt.main(["--serial", "192.168.1.104:40569"]), 2)

    def test_every_event_has_the_required_fields(self):
        for e in wt.EVENTS:
            for key in ("name", "pre", "do", "undo", "expect"):
                self.assertIn(key, e)
            self.assertTrue(e["do"], e["name"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
