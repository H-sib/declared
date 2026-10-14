"""Unit tests for the pure functions in tripwire_emulator.py. Run: python tools/test_tripwire_emulator.py"""
import csv
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import tripwire_emulator as tw  # noqa: E402


class KeyStateTests(unittest.TestCase):
    def test_extracts_both_keys_and_flags(self):
        v = {"keys": {"lab_trip_bio": "VALID", "lab_trip_any": "INVALIDATED"}, "deviceSecure": True, "biometricStrongState": 0}
        self.assertEqual(tw.key_states(v), ("VALID", "INVALIDATED", True, 0))

    def test_missing_value_gives_nones(self):
        self.assertEqual(tw.key_states(None), (None, None, None, None))
        self.assertEqual(tw.key_states({}), (None, None, None, None))

    def test_classify(self):
        self.assertEqual(tw.classify("VALID"), "VALID")
        self.assertEqual(tw.classify("INVALIDATED"), "INVALIDATED")
        self.assertEqual(tw.classify("CREATE_FAILED:InvalidAlgorithmParameterException:x"), "FAILED")
        self.assertEqual(tw.classify("OTHER:UserNotAuthenticatedException:y"), "OTHER")
        self.assertIsNone(tw.classify(None))


class CsvTests(unittest.TestCase):
    def test_rows_round_trip_with_header_once(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "t.csv")
            tw.append_csv(tw.row("34", "s1", 1, True, "VALID", "VALID", 0), path)
            tw.append_csv(tw.row("34", "s2", 2, True, "INVALIDATED", "VALID", 0, "n"), path)
            rows = list(csv.DictReader(open(path, encoding="utf-8")))
        self.assertEqual(len(rows), 2)
        self.assertEqual(rows[1]["key_biometric_only"], "INVALIDATED")
        self.assertEqual(list(rows[0].keys()), tw.FIELDS)


class KeyguardTests(unittest.TestCase):
    def test_showing_variants(self):
        self.assertTrue(tw.parse_keyguard_showing("  mIsShowing=true\n"))
        self.assertTrue(tw.parse_keyguard_showing("KeyguardServiceDelegate: showing=true\n"))
        self.assertTrue(tw.parse_keyguard_showing("keyguardShowing=true"))

    def test_not_showing(self):
        self.assertFalse(tw.parse_keyguard_showing("showing=false\n  mIsShowing=false\n"))
        self.assertFalse(tw.parse_keyguard_showing(""))
        self.assertFalse(tw.parse_keyguard_showing(None))

    def test_older_android_spelling(self):
        self.assertTrue(tw.parse_keyguard_showing("mShowingLockscreen=true mShowingDream=false"))
        self.assertFalse(tw.parse_keyguard_showing("mShowingLockscreen=false mShowingDream=false"))

    def test_a_word_containing_showing_does_not_match(self):
        self.assertFalse(tw.parse_keyguard_showing("notshowing=true"))


class SafetyTests(unittest.TestCase):
    def test_refuses_a_non_emulator_serial(self):
        self.assertEqual(tw.main(["--serial", "192.168.1.104:40569"]), 2)
        self.assertEqual(tw.main(["--serial", "R5CT1234567"]), 2)


if __name__ == "__main__":
    unittest.main(verbosity=2)
