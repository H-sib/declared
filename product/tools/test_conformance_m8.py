"""Unit tests for the pure helpers of conformance_m8.py, run against the committed sample history."""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import conformance_m8 as m8  # noqa: E402
import ledger_oracle as oracle  # noqa: E402

SAMPLE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "core", "src", "test", "resources", "ledger", "sample-ledger.json")


def text():
    with open(SAMPLE, encoding="utf-8") as f:
        return f.read()


class Helpers(unittest.TestCase):
    def test_new_events_are_only_those_after_the_given_entry(self):
        self.assertIn("tripwire.fingerprint-added", m8.new_events(text(), 0))
        self.assertNotIn("tripwire.fingerprint-added", m8.new_events(text(), 5))
        self.assertEqual([], m8.new_events(text(), 7))

    def test_new_changes_are_kind_and_key(self):
        self.assertEqual({("M", "fact:HTTP_PROXY"), ("A", "finding:condition:http-proxy")}, m8.new_changes(text(), 1) & {("M", "fact:HTTP_PROXY"), ("A", "finding:condition:http-proxy")})
        self.assertEqual(set(), m8.new_changes(text(), 7))

    def test_third_party_packages_from_pm_output(self):
        self.assertEqual({"a.b", "c.d"}, m8.third_party("package:a.b\r\npackage:c.d\r\n\r\nWarning: noise\n"))

    def test_launcher_packages_come_from_the_query_output(self):
        out = "\n".join(["2 activities found:", "  priority=0 preferredOrder=0 match=0x108000", "  a.b/.Main", "  priority=0", "  c.d/c.d.Other", "not a line", ""])
        self.assertEqual({"a.b", "c.d"}, m8.launcher_packages(out))

    def test_the_title_a_finding_had_when_it_appeared(self):
        self.assertEqual(["A web proxy is set for this connection"], m8.titles_added(text(), "finding:condition:http-proxy"))
        self.assertEqual([], m8.titles_added(text(), "finding:nothing"))

    def test_final_records_come_from_replaying_every_entry(self):
        records = oracle.final_records(text())
        self.assertIn("app:odd", records)
        self.assertNotIn("app:evil.app", records)


if __name__ == "__main__":
    unittest.main()
