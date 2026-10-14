"""Unit tests for tripwire_summary.py. Run: python tools/test_tripwire_summary.py"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import tripwire_summary as ts  # noqa: E402


def row(api, step, fp, bio, any_=None):
    return {"api": api, "step": step, "enrolled_fingerprints": str(fp), "secure_lock": "True",
            "key_biometric_only": bio, "key_biometric_or_credential": any_ if any_ is not None else bio}


def full_run(api, k6_fp=2, k6_bio="INVALIDATED", lock_bio="MISSING"):
    return [
        row(api, "A baseline (1 fingerprint): create keys", 1, "CREATED"),
        row(api, "A control: check again", 1, "VALID"),
        row(api, "K6 after enrolling fingerprint 2", k6_fp, k6_bio, "VALID"),
        row(api, "S after reinstalling the app (-r)", k6_fp, "VALID"),
        row(api, "S after reboot (unlocked)", k6_fp, "VALID"),
        row(api, "K7a after CHANGING the PIN", k6_fp, "VALID"),
        row(api, "K7b after REMOVING the lock", 0, lock_bio),
        row(api, "K7c create keys with no lock (can it?)", 0,
            "CREATE_FAILED:InvalidAlgorithmParameterException:java.lang.IllegalStateException: At least one biometric must be enrolled",
            "CREATE_FAILED:InvalidAlgorithmParameterException:java.lang.IllegalStateException: Secure lock screen must be enabled"),
    ]


class RunTests(unittest.TestCase):
    def test_split_runs_on_baseline_rows(self):
        rows = full_run("34") + full_run("34")
        self.assertEqual(len(ts.split_runs(rows)), 2)

    def test_incomplete_run_is_not_complete(self):
        run = full_run("34")[:5]
        self.assertFalse(ts.is_complete(run))
        self.assertTrue(ts.is_complete(full_run("34")))

    def test_latest_complete_run_wins(self):
        rows = full_run("35", k6_fp=1, k6_bio="VALID") + full_run("35")
        s = ts.summarize(rows)["35"]
        self.assertEqual(s["runs_complete"], 2)
        self.assertEqual(s["fingerprint added: biometric-only key"], "invalidated")


class CellTests(unittest.TestCase):
    def test_k6_cell_is_flagged_when_the_second_fingerprint_never_registered(self):
        s = ts.summarize(full_run("36", k6_fp=1, k6_bio="VALID"))["36"]
        self.assertTrue(s["fingerprint added: biometric-only key"].startswith("NOT EXERCISED"))

    def test_other_cells_are_not_flagged_for_fingerprint_count(self):
        s = ts.summarize(full_run("36", k6_fp=1, k6_bio="VALID"))["36"]
        self.assertEqual(s["app reinstalled"], "valid")
        self.assertEqual(s["lock removed"], "missing")

    def test_short_states(self):
        self.assertEqual(ts.short("OTHER:UnrecoverableKeyException:Failed"), "unusable (UnrecoverableKeyException)")
        self.assertEqual(ts.short("CREATE_FAILED:X:Secure lock screen must be enabled"), "refused (needs lock)")
        self.assertEqual(ts.short(None), "no answer")
        self.assertEqual(ts.short("CREATE_FAILED:KeyStoreException:Failed to delete entry: k"), "failed (stale key)")

    def test_markdown_has_one_row_per_api_and_handles_missing_runs(self):
        md = ts.to_markdown(ts.summarize(full_run("34") + full_run("29")[:3]))
        self.assertEqual(md.count("\n"), 3)  # header, separator, two data rows
        self.assertIn("no complete run", md)


if __name__ == "__main__":
    unittest.main(verbosity=2)
