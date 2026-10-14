"""Unit tests for the pure functions in emu_fingerprint.py. Run: python tools/test_emu_fingerprint.py"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import emu_fingerprint as ef  # noqa: E402
import ui  # noqa: E402


def node(text="", rid="", clickable=False, desc=""):
    return {"text": text, "desc": desc, "rid": rid, "bounds": (0, 0, 10, 10), "clickable": clickable, "enabled": True}


class CountTests(unittest.TestCase):
    def test_parses_zero_and_nonzero(self):
        zero = '{"service":"FingerprintProvider\\/default","prints":[{"id":0,"count":0,"accept":0}]}'
        two = '{"service":"x","prints":[{"id":0,"count":2,"accept":10}]}'
        self.assertEqual(ef.parse_enrolled_count(zero), 0)
        self.assertEqual(ef.parse_enrolled_count(two), 2)

    def test_unreadable_gives_none(self):
        self.assertIsNone(ef.parse_enrolled_count("nothing useful"))
        self.assertIsNone(ef.parse_enrolled_count(None))


class LaunchTests(unittest.TestCase):
    def test_enrollment_launches_in_a_fresh_task(self):
        cmd = ef.enroll_launch_command()
        self.assertIn("android.settings.FINGERPRINT_ENROLL", cmd)
        self.assertIn(" -S ", cmd)  # force-stop Settings first
        flags = int(cmd.split("-f ")[1].split()[0], 16)
        self.assertTrue(flags & 0x10000000, "NEW_TASK missing")
        self.assertTrue(flags & 0x8000, "CLEAR_TASK missing")


class ClassifyTests(unittest.TestCase):
    def test_pin_prompt_by_text_and_by_resource_id(self):
        self.assertEqual(ef.classify_screen([node("Confirm your PIN")]), "pin")
        self.assertEqual(ef.classify_screen([node("", rid="com.android.settings:id/password_entry")]), "pin")

    def test_intro_description_mentioning_the_sensor_is_not_a_sensor_prompt(self):
        # Regression (D-011): the Android 10 intro screen says "Just touch the fingerprint sensor to unlock your phone".
        nodes = [node("Unlock with fingerprint"), node("Just touch the fingerprint sensor to unlock your phone, authorize purchases"),
                 node("CANCEL", clickable=True), node("NEXT", clickable=True)]
        self.assertEqual(ef.classify_screen(nodes), "button:^next$")

    def test_real_sensor_screens_still_match(self):
        for text in ("Touch the sensor", "Lift, then touch again", "Lift finger, then touch sensor again", "Put your finger on the sensor"):
            self.assertEqual(ef.classify_screen([node(text)]), "sensor", text)

    def test_sensor_prompt(self):
        self.assertEqual(ef.classify_screen([node("Touch the sensor")]), "sensor")
        self.assertEqual(ef.classify_screen([node("Lift, then touch again")]), "sensor")

    def test_button_priority_agree_before_more(self):
        nodes = [node("MORE", clickable=True), node("I AGREE", clickable=True)]
        self.assertEqual(ef.classify_screen(nodes), "button:^(i )?agree$")

    def test_non_clickable_button_is_matched_by_text_as_a_fallback(self):
        # Regression from the Android 10 sweep: its setup buttons are not marked clickable in the UI dump.
        self.assertEqual(ef.classify_screen([node("NEXT", clickable=False), node("CANCEL", clickable=False)]), "button:^next$")

    def test_clickable_button_still_wins_over_a_non_clickable_one(self):
        nodes = [node("NEXT", clickable=False), node("MORE", clickable=True)]
        self.assertEqual(ef.classify_screen(nodes), "button:^more$")

    def test_add_fingerprint_button_on_the_fingerprint_list(self):
        self.assertEqual(ef.classify_screen([node("Fingerprint 1", clickable=True), node("Add fingerprint", clickable=True)]),
                         "button:^add (a )?fingerprint$")

    def test_unknown_screen(self):
        self.assertEqual(ef.classify_screen([node("Settings")]), "unknown")


if __name__ == "__main__":
    unittest.main(verbosity=2)
