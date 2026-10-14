import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import heartbeat_report as h  # noqa: E402

START = h.to_ms("2026-10-08 17:00")


def line(minutes, bucket=30):
    return f"{START + minutes * 60000},ran bucket={bucket} bgRestricted=false ignoringBatteryOptimizations=false"


class Report(unittest.TestCase):
    def test_only_run_lines_count(self):
        text = f"{START},scheduled result=1\n{line(15)}\n{START + 5},cancelled\nnot a line\n{line(30, 40)}\n"
        self.assertEqual([(START + 15 * 60000, 30), (START + 30 * 60000, 40)], h.parse_runs(text))

    def test_a_perfect_window_delivers_every_run_and_has_no_long_gap(self):
        runs = h.parse_runs("\n".join(line(m) for m in range(15, 181, 15)))
        s = h.window_stats(runs, START, START + 180 * 60000)
        self.assertEqual((12, 12), (s["delivered"], s["expected"]))
        self.assertEqual([], s["over_30"])

    def test_a_missing_stretch_is_the_longest_gap_and_a_gap_over_thirty_minutes(self):
        runs = h.parse_runs("\n".join(line(m) for m in (15, 30, 45, 150, 165, 180)))
        s = h.window_stats(runs, START, START + 180 * 60000)
        self.assertEqual(6, s["delivered"])
        self.assertEqual(105, (s["longest"][1] - s["longest"][0]) // 60000)
        self.assertEqual(1, len(s["over_30"]))

    def test_bucket_changes_are_listed_in_order_with_names(self):
        runs = h.parse_runs("\n".join([line(15, 10), line(30, 10), line(45, 30), line(60, 40)]))
        s = h.window_stats(runs, START, START + 60 * 60000)
        self.assertEqual([("ACTIVE", "FREQUENT"), ("FREQUENT", "RARE")], [(a, b) for _, a, b in s["bucket_changes"]])

    def test_the_report_says_when_there_is_nothing(self):
        self.assertEqual("no runs in the log", h.report([], []))


if __name__ == "__main__":
    unittest.main()
