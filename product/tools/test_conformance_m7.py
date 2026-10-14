"""Unit tests for the pure helpers of conformance_m7.py."""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import conformance_m7 as c  # noqa: E402
import paths_oracle as o  # noqa: E402


class Words(unittest.TestCase):
    def test_the_plan_sentence_in_every_shape(self):
        self.assertEqual("Making this 1 change stops the 1 path this app can see.", c.plan_sentence(1, 1, 1))
        self.assertEqual("Making these 3 changes stops all 3 paths this app can see.", c.plan_sentence(3, 3, 3))
        self.assertEqual("Making these 2 changes stops 2 of the 4 paths this app can see.", c.plan_sentence(2, 2, 4))

    def test_the_remaining_and_count_sentences(self):
        self.assertEqual("With the changes you ticked, none of these paths would remain.", c.remaining_sentence(0))
        self.assertEqual("With the changes you ticked, 1 path would remain.", c.remaining_sentence(1))
        self.assertEqual("With the changes you ticked, 4 paths would remain.", c.remaining_sentence(4))
        self.assertEqual("1 path this app can see.", c.path_count_sentence(1))
        self.assertEqual("3 paths this app can see.", c.path_count_sentence(3))

    def test_the_sentences_are_the_ones_core_defines(self):
        path = os.path.join(c.PRODUCT, "core", "src", "main", "kotlin", "io", "github", "h_sib", "declared", "core", "paths", "PathsWords.kt")
        with open(path, encoding="utf-8") as fh:
            source = fh.read()
        for fragment in ("this 1 change", "these $levers changes", "stops the 1 path this app can see", "stops all $total paths this app can see",
                         "stops $cut of the $total paths this app can see", "With the changes you ticked, none of these paths would remain.",
                         "With the changes you ticked, 1 path would remain.", "paths would remain.", "$n paths this app can see.", "1 path this app can see.",
                         c.HIGH_RISK_LABEL, "Nothing is changed on your phone."):
            self.assertIn(fragment, source if fragment != "Nothing is changed on your phone." else source)
        for lever, words in c.LEVER_WORDS.items():
            rules = os.path.join(c.PRODUCT, "core", "src", "main", "kotlin", "io", "github", "h_sib", "declared", "core", "paths", "RuleBook.kt")
            with open(rules, encoding="utf-8") as fh:
                self.assertIn(f'"{lever}"', fh.read())
            self.assertTrue(words)


class Answers(unittest.TestCase):
    def test_any_doubt_wins_all_recognized_is_recognized_and_the_rest_is_nothing(self):
        self.assertEqual("NOT_RECOGNIZED", c.aggregate_answer(["RECOGNIZED", "NOT_RECOGNIZED", "UNSURE"]))
        self.assertEqual("RECOGNIZED", c.aggregate_answer(["RECOGNIZED", "RECOGNIZED"]))
        self.assertIsNone(c.aggregate_answer(["RECOGNIZED", "UNSURE"]))
        self.assertIsNone(c.aggregate_answer([]))


def truth(**over):
    t = {"lock_set": False, "patch_age_days": 1000, "advanced_protection": "unsupported", "network_present": True, "proxy": False, "private_dns_active": False,
         "wifi_connected": True, "wifi_open": True, "user_cas": False}
    t.update(over)
    return t


TOLD = {"sms_codes": "YES", "public_wifi": "YES", "profile": "GENERAL"}


_OF_THE_ORACLE = object()


def app_paths(result, told=TOLD, plan=_OF_THE_ORACLE, extra_paths=()):
    return {
        "profile": told["profile"], "declarations": {"smsCodes": told["sms_codes"], "publicWifi": told["public_wifi"], "profile": told["profile"]},
        "paths": [{"id": g, "severity": result["severity"][g], "confidence": o.CONF_NAME[result["paths"][g][1]], "restsOnWhatYouSaid": g.startswith("p2:"), "stoppedBy": []} for g in sorted(result["paths"])] + list(extra_paths),
        "notRuledOut": [{"id": g, "needsAnAnswer": False, "because": []} for g in sorted(result["open"])],
        "plan": ({"levers": result["plan"]["levers"], "exact": True, "cut": len(result["plan"]["cut"]), "unstoppable": result["plan"]["unstoppable"]}
                 if plan is _OF_THE_ORACLE else plan),
    }


class Compare(unittest.TestCase):
    def failures(self, app, result, told=TOLD, answers=None, skip=()):
        return [x.name for x in c.compare_paths(app, result, told, answers or {}, skip) if not x.ok]

    def test_an_app_that_agrees_with_the_oracle_passes_every_check(self):
        result = o.analyse(truth(), TOLD, {})
        self.assertEqual([], self.failures(app_paths(result), result))

    def test_a_missing_path_an_extra_path_and_a_wrong_severity_are_caught(self):
        result = o.analyse(truth(), TOLD, {})
        app = app_paths(result)
        app["paths"] = app["paths"][1:]
        self.assertIn("the paths that exist = the oracle's", self.failures(app, result))
        extra = app_paths(result, extra_paths=[{"id": "p1:ghost", "severity": "HIGH", "confidence": "HIGH", "restsOnWhatYouSaid": False, "stoppedBy": []}])
        self.assertIn("the paths that exist = the oracle's", self.failures(extra, result))
        wrong = app_paths(result)
        wrong["paths"][0]["severity"] = "LOW"
        self.assertIn("severity of each path = THREAT_MODEL.md section 6 for that profile", self.failures(wrong, result))

    def test_a_different_plan_or_an_approximate_plan_is_caught(self):
        result = o.analyse(truth(), TOLD, {})
        other = app_paths(result, plan={"levers": ["remove-ca"], "exact": True, "cut": 1, "unstoppable": []})
        self.assertIn("the smallest set of changes (ids) = the oracle's brute force", self.failures(other, result))
        approx = app_paths(result)
        approx["plan"]["exact"] = False
        self.assertIn("the answer is proven exact, not approximate", self.failures(approx, result))

    def test_a_goal_held_open_is_compared_separately_from_goals_that_exist(self):
        told = {**TOLD, "public_wifi": "UNSURE", "sms_codes": "UNSURE"}
        result = o.analyse(truth(wifi_open=False), told, {})
        self.assertIn("p3-wifi", result["open"])
        self.assertEqual([], self.failures(app_paths(result, told), result, told))
        dropped = app_paths(result, told)
        dropped["notRuledOut"] = []
        self.assertIn("the paths held open = the oracle's", self.failures(dropped, result, told))

    def test_with_no_path_there_is_no_plan(self):
        told = {**TOLD, "public_wifi": "NO"}
        result = o.analyse(truth(lock_set=True, patch_age_days=10, wifi_open=False, private_dns_active=True), told, {})
        self.assertEqual({}, result["paths"])
        self.assertEqual([], self.failures(app_paths(result, told, plan=None), result, told))
        self.assertIn("with no path there is no plan", self.failures(app_paths(result, told, plan={"levers": [], "exact": True, "cut": 0, "unstoppable": []}), result, told))

    def test_a_skipped_goal_is_not_compared(self):
        result = o.analyse(truth(user_cas=True), TOLD, {})
        app = app_paths(result)
        app["paths"] = [p for p in app["paths"] if p["id"] != "p3-ca"]
        self.assertNotEqual([], self.failures(app, result))
        self.assertNotIn("the paths that exist = the oracle's", self.failures(app, result, skip=("p3-ca",)))


if __name__ == "__main__":
    unittest.main()
