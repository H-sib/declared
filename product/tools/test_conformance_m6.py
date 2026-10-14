"""Unit tests for the pure helpers of conformance_m6.py."""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import conformance_m6 as c  # noqa: E402

PKG = "io.github.h_sib.declared.debug"

DUMPSYS_SECURE = f"""
  Window #8 Window{{aaaa u0 com.google.android.apps.nexuslauncher/com.google.android.apps.nexuslauncher.NexusLauncherActivity}}:
    mOwnerUid=10100
    mAttrs={{(0,0)(fillxfill) ty=BASE_APPLICATION
      fl=LAYOUT_IN_SCREEN LAYOUT_INSET_DECOR SPLIT_TOUCH HARDWARE_ACCELERATED
  Window #9 Window{{74ad0b2 u0 {PKG}/io.github.h_sib.declared.app.MainActivity}}:
    mDisplayId=0 rootTaskId=128
    mAttrs={{(0,0)(fillxfill) sim={{adjust=pan forwardNavigation}} ty=BASE_APPLICATION wanim=0x10302f2
      fl=LAYOUT_IN_SCREEN SECURE LAYOUT_INSET_DECOR SPLIT_TOUCH HARDWARE_ACCELERATED DRAWS_SYSTEM_BAR_BACKGROUNDS
      pfl=NO_MOVE_ANIMATION
"""


def event(at, what):
    return {"at": at, "source": "SETTING", "what": what}


class Time(unittest.TestCase):
    def test_instants_with_any_number_of_fraction_digits(self):
        self.assertEqual(1_000_000_000 * 1760000000, c.iso_to_ns("2025-10-09T08:53:20Z"))
        self.assertEqual(1760000000_123_000_000, c.iso_to_ns("2025-10-09T08:53:20.123Z"))
        self.assertEqual(1760000000_123_456_000, c.iso_to_ns("2025-10-09T08:53:20.123456Z"))
        self.assertEqual(1760000000_123_456_789, c.iso_to_ns("2025-10-09T08:53:20.123456789Z"))

    def test_garbage_is_refused(self):
        for bad in ("", None, "2025-10-09 08:53:20", "2025-10-09T08:53:20+02:00"):
            with self.assertRaises(ValueError):
                c.iso_to_ns(bad)


class Events(unittest.TestCase):
    live = {
        "events": [
            event("2026-10-08T21:40:00.500Z", "The web proxy setting changed"),
            event("2026-10-08T21:40:00.100Z", "The default keyboard changed"),
            event("2026-10-08T21:39:00.000Z", "The web proxy setting changed"),
        ],
        "reportTakenAt": "2026-10-08T21:40:01.000Z",
        "findingIds": ["accessibility:lab.fixture.a11y/lab.fixture.FixtureA11yService"],
    }
    base = c.iso_to_ns("2026-10-08T21:40:00.000Z")

    def test_only_events_at_or_after_the_change_count_and_they_come_oldest_first(self):
        found = c.events_after(self.live, "The web proxy setting changed", self.base)
        self.assertEqual(["2026-10-08T21:40:00.500Z"], [e["at"] for e in found])

    def test_latency_is_from_the_clock_before_the_change_to_the_first_announcement(self):
        self.assertEqual(500.0, c.latency_ms(self.live, "The web proxy setting changed", self.base))
        self.assertEqual(100.0, c.latency_ms(self.live, "The default keyboard changed", self.base))
        self.assertIsNone(c.latency_ms(self.live, "Never said", self.base))
        self.assertIsNone(c.latency_ms(None, "x", 0))

    def test_regex_matching_returns_the_words_of_each_match(self):
        self.assertEqual(["The web proxy setting changed"], c.events_matching(self.live, "proxy", self.base))
        self.assertEqual([], c.events_matching(self.live, "installed", 0))

    def test_the_report_must_be_taken_after_the_change(self):
        self.assertTrue(c.read_again_after(self.live, self.base))
        self.assertFalse(c.read_again_after(self.live, c.iso_to_ns("2026-10-08T21:40:02Z")))
        self.assertFalse(c.read_again_after({}, 0))

    def test_findings_are_found_by_fragment(self):
        self.assertTrue(c.has_finding(self.live, "lab.fixture.a11y"))
        self.assertFalse(c.has_finding(self.live, "lab.fixture.admin"))
        self.assertFalse(c.has_finding(None, "x"))

    def test_the_value_is_not_vacuous_for_a_change_that_was_announced_earlier(self):
        # the keyboard change is before the base time of a later change and must not be counted for it
        self.assertEqual([], c.events_after(self.live, "The default keyboard changed", c.iso_to_ns("2026-10-08T21:40:00.200Z")))


class Window(unittest.TestCase):
    def test_the_apps_window_is_secure(self):
        self.assertTrue(c.window_secure(DUMPSYS_SECURE, PKG))

    def test_another_windows_flags_do_not_count(self):
        self.assertFalse(c.window_secure(DUMPSYS_SECURE.replace(" SECURE", ""), PKG))
        only_launcher = DUMPSYS_SECURE.split("Window #9")[0]
        self.assertIsNone(c.window_secure(only_launcher, PKG))

    def test_the_word_must_be_a_whole_flag(self):
        self.assertFalse(c.window_secure(DUMPSYS_SECURE.replace(" SECURE ", " INSECURE_X "), PKG))


class Accessibility(unittest.TestCase):
    def node(self, bounds, text="", desc="", clickable=False, enabled=True):
        return {"bounds": bounds, "text": text, "desc": desc, "clickable": clickable, "enabled": enabled}

    def test_forty_eight_dp_in_pixels(self):
        self.assertAlmostEqual(126.0, c.dp_to_px(48, 420))
        self.assertAlmostEqual(48.0, c.dp_to_px(48, 160))

    def test_small_clickables_are_reported_and_big_or_unclickable_ones_are_not(self):
        frame = self.node((0, 0, 1080, 2000))
        small = self.node((0, 300, 100, 500), "Back", clickable=True)
        thin = self.node((0, 300, 400, 420), "Edge", clickable=True)
        fine = self.node((0, 300, 126, 426), "Exactly", clickable=True)
        text = self.node((0, 300, 20, 320), "Not clickable")
        off = self.node((0, 300, 10, 310), "Disabled", clickable=True, enabled=False)
        problems = c.touch_target_problems([frame, small, thin, fine, text, off], 420)
        self.assertEqual(2, len(problems))
        self.assertTrue(problems[0].startswith("Back is 100x200px"))
        self.assertTrue(problems[1].startswith("Edge is 400x120px"))

    def test_a_tall_card_cut_off_by_the_edge_of_the_window_is_not_a_small_target_but_a_short_one_in_the_middle_is(self):
        frame = self.node((0, 0, 1080, 2000))
        cut_at_the_bottom = self.node((42, 1940, 1038, 2000), clickable=True)
        cut_at_the_top = self.node((42, 0, 1038, 50), clickable=True)
        short_in_the_middle = self.node((42, 1000, 1038, 1060), clickable=True)
        problems = c.touch_target_problems([frame, cut_at_the_bottom, cut_at_the_top, short_in_the_middle], 420)
        self.assertEqual(1, len(problems))
        self.assertIn("top 1000", problems[0])

    def test_a_clickable_card_made_of_texts_has_words_when_a_text_is_inside_it(self):
        card = self.node((0, 100, 1000, 400), clickable=True)
        inside = self.node((20, 120, 500, 160), "Live view")
        outside = self.node((20, 500, 500, 560), "Elsewhere")
        self.assertEqual([], c.clickable_label_problems([card, inside]))
        self.assertEqual(1, len(c.clickable_label_problems([card, outside])))
        self.assertEqual([], c.clickable_label_problems([self.node((0, 0, 10, 10), desc="Close", clickable=True)]))

    def test_a_clickable_with_nothing_at_all_is_reported(self):
        self.assertEqual(1, len(c.clickable_label_problems([self.node((0, 0, 10, 10), clickable=True)])))


class Checks(unittest.TestCase):
    def test_within_passes_only_below_the_limit_and_records_the_rounded_number(self):
        ok = c.Within("x", 3000, 1234.6)
        self.assertTrue(ok.ok)
        self.assertEqual(1235, ok.actual)
        self.assertFalse(c.Within("x", 3000, 3000).ok)
        self.assertFalse(c.Within("x", 3000, None).ok)

    def test_the_announcement_words_are_the_ones_core_defines(self):
        # core/live/Live.kt is the source of these sentences; this script repeats them on purpose, so drift fails loudly.
        path = os.path.join(c.PRODUCT, "core", "src", "main", "kotlin", "io", "github", "h_sib", "declared", "core", "live", "Live.kt")
        with open(path, encoding="utf-8") as fh:
            source = fh.read()
        for words in c.WORDS.values():
            self.assertIn(f'"{words}"', source)
        self.assertIn(f"POLL_SECONDS = {c.POLL_SECONDS}L", source)


if __name__ == "__main__":
    unittest.main()
