"""Unit tests for paths_oracle.py, including the same worst-case phone the Kotlin RuleBookTest uses (two implementations, one answer)."""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import paths_oracle as o  # noqa: E402

TOLD_NONE = {"sms_codes": "UNSURE", "public_wifi": "UNSURE", "profile": "GENERAL"}


def quiet_truth(**over):
    t = {
        "lock_set": True, "patch_age_days": 30, "advanced_protection": "unsupported", "network_present": True, "proxy": False,
        "private_dns_active": True, "wifi_connected": False, "wifi_open": None, "user_cas": False,
    }
    t.update(over)
    return t


def app(**over):
    a = {"tier_unusual": True, "tier_confidence": "MEDIUM", "a11y_on": False, "a11y_window_content": True, "notif_on": False, "sms": False,
         "admin_on": False, "overlay": False, "hidden_icon": False, "location": False, "mic": False, "built_in": False}
    a.update(over)
    return a


KEYS = {"a11y": {"shadow.app": "shadow.app/shadow.app.Service"}, "notif": {"shadow.app": "shadow.app/shadow.app.Service"},
        "admin": {"shadow.app": "shadow.app/shadow.app.Admin"}}


class Logic(unittest.TestCase):
    def test_kleene_tables_and_confidence_rules(self):
        v = {"a": (o.T, "HIGH"), "b": (o.T, "LOW"), "c": (o.U, "LOW"), "d": (o.F, "HIGH")}
        self.assertEqual((o.T, 0), o.evaluate(("and", ("var", "a"), ("var", "b")), v))
        self.assertEqual((o.T, 2), o.evaluate(("or", ("var", "a"), ("var", "b")), v))
        self.assertEqual((o.U, None), o.evaluate(("and", ("var", "a"), ("var", "c")), v))
        self.assertEqual((o.F, None), o.evaluate(("and", ("var", "d"), ("var", "c")), v))
        self.assertEqual((o.T, 2), o.evaluate(("or", ("var", "c"), ("var", "a")), v))
        self.assertEqual((o.U, None), o.evaluate(("or", ("var", "c"), ("var", "d")), v))

    def test_a_clean_phone_has_no_path_and_nothing_open(self):
        r = o.analyse(quiet_truth(), {**TOLD_NONE, "public_wifi": "NO"}, {})
        self.assertEqual({}, r["paths"])
        self.assertEqual(set(), r["open"])
        self.assertEqual([], r["plan"]["levers"])

    def test_not_told_keeps_the_wifi_path_open_only_when_private_dns_is_off(self):
        self.assertEqual(set(), o.analyse(quiet_truth(), TOLD_NONE, {})["open"])
        self.assertEqual({"p3-wifi"}, o.analyse(quiet_truth(private_dns_active=False), TOLD_NONE, {})["open"])

    def test_the_patch_limit_depends_on_the_profile(self):
        t = quiet_truth(patch_age_days=281)
        self.assertNotIn("p6-patch", o.analyse(t, TOLD_NONE, {})["paths"])
        self.assertIn("p6-patch", o.analyse(t, {**TOLD_NONE, "profile": "HIGH_RISK"}, {})["paths"])

    def test_advanced_protection_is_not_a_weakness_where_it_does_not_exist(self):
        self.assertNotIn("p6-advanced-protection", o.analyse(quiet_truth(advanced_protection="unsupported"), TOLD_NONE, {})["paths"])
        self.assertIn("p6-advanced-protection", o.analyse(quiet_truth(advanced_protection="off"), TOLD_NONE, {})["paths"])
        self.assertIn("p6-advanced-protection", o.analyse(quiet_truth(advanced_protection="unknown"), TOLD_NONE, {})["open"])

    def test_an_answer_decides_whether_the_app_is_a_reason_to_look(self):
        apps = {"x.app": app(a11y_on=True)}
        self.assertIn("p1:x.app", o.analyse(quiet_truth(), TOLD_NONE, apps, keys={"a11y": {"x.app": "x.app/s"}, "notif": {}, "admin": {}})["paths"])
        recognized = o.analyse(quiet_truth(), TOLD_NONE, apps, answers={"x.app": "RECOGNIZED"}, keys={"a11y": {"x.app": "x.app/s"}, "notif": {}, "admin": {}})
        self.assertNotIn("p1:x.app", recognized["paths"])
        changed = o.analyse(quiet_truth(), TOLD_NONE, apps, answers={"x.app": "RECOGNIZED"}, signer_changed={"x.app"}, keys={"a11y": {"x.app": "x.app/s"}, "notif": {}, "admin": {}})
        self.assertIn("p1:x.app", changed["paths"])


class WorstCase(unittest.TestCase):
    """The same phone as RuleBookTest.worstCase in Kotlin."""

    def result(self):
        truth = quiet_truth(lock_set=False, patch_age_days=1000, advanced_protection="off", proxy=True, private_dns_active=False,
                            wifi_connected=True, wifi_open=True, user_cas=True)
        told = {"sms_codes": "YES", "public_wifi": "YES", "profile": "GENERAL"}
        shadow = app(a11y_on=True, notif_on=True, admin_on=True, sms=True, hidden_icon=True, location=True)
        return o.analyse(truth, told, {"shadow.app": shadow}, keys=KEYS)

    def test_the_paths_of_every_rule(self):
        self.assertEqual(
            {"p1:shadow.app", "p2:shadow.app", "p5:shadow.app", "p3-ca", "p3-proxy", "p3-wifi", "p4-no-lock", "p6-patch", "p6-advanced-protection"},
            set(self.result()["paths"]),
        )

    def test_the_smallest_fix_is_the_one_kotlin_finds(self):
        plan = self.result()["plan"]
        self.assertEqual(
            ["advanced-protection-on", "clear-proxy", "private-dns-on", "remove-ca", "remove:shadow.app", "set-lock", "update-phone"],
            plan["levers"],
        )
        self.assertEqual(9, len(plan["cut"]))
        self.assertEqual([], plan["unstoppable"])

    def test_severity_follows_the_profile(self):
        r = self.result()
        self.assertEqual("HIGH", r["severity"]["p1:shadow.app"])
        self.assertEqual("LOW", r["severity"]["p6-advanced-protection"])

    def test_a_weakest_link_confidence_for_the_phone_level_paths(self):
        r = self.result()
        self.assertEqual(o.CONF["MEDIUM"], r["paths"]["p3-ca"][1])
        self.assertEqual(o.CONF["HIGH"], r["paths"]["p3-proxy"][1])
        self.assertEqual(o.CONF["MEDIUM"], r["paths"]["p2:shadow.app"][1])


class Fixing(unittest.TestCase):
    def test_the_gentler_of_two_equal_fixes_wins_and_a_shared_lever_beats_two(self):
        truth = quiet_truth(private_dns_active=False, wifi_connected=True, wifi_open=True)
        r = o.analyse(truth, {**TOLD_NONE, "public_wifi": "YES"}, {})
        self.assertEqual(["private-dns-on"], r["plan"]["levers"])

    def test_a_path_nothing_can_stop_is_reported_as_such(self):
        # no lever exists for a fact that is true and has no lever: build such a case by hand
        goals = {"g": ("var", "fact")}
        values = {"fact": (o.T, "HIGH")}
        plan = o.smallest_fix(goals, values, {}, {"g": (o.T, 2)})
        self.assertEqual({"levers": [], "cut": [], "unstoppable": ["g"]}, plan)

    def test_removing_the_app_cuts_everything_that_depended_on_it(self):
        shadow = app(a11y_on=True, notif_on=True, sms=True)
        r = o.analyse(quiet_truth(), {**TOLD_NONE, "sms_codes": "YES"}, {"shadow.app": shadow}, keys=KEYS)
        self.assertEqual({"p1:shadow.app", "p2:shadow.app"}, set(r["paths"]))
        self.assertEqual(["remove:shadow.app"], r["plan"]["levers"])


if __name__ == "__main__":
    unittest.main()
