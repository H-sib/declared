"""Unit tests for the pure helpers and the comparator of conformance_m2.py, built from shapes seen on the API 34 emulator."""
import copy
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import conformance_m2 as c  # noqa: E402

A11Y = "lab.fixture.a11y/lab.fixture.FixtureA11yService"
NOTIF = "lab.fixture.notif/lab.fixture.FixtureNotifListener"
ADMIN = "lab.fixture.admin/lab.fixture.FixtureAdminReceiver"
IME = "lab.fixture.ime/lab.fixture.FixtureIme"
OTHER_IME = "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"
PERMS = "lab.fixture.perms"
SYSTEM_APP = "com.android.settings"
ALL_SPECIAL = {"OVERLAY": "ALLOWED", "USAGE_ACCESS": "ALLOWED", "INSTALL_UNKNOWN_APPS": "ALLOWED"}


def pkg_row(pkg, system=False, installing=None, initiating="com.android.shell", granted=(), label=None):
    return {"pkg": pkg, "label": label or pkg, "uid": 10001, "system": system, "updatedSystem": False, "platformSigned": False,
            "installing": installing, "initiating": initiating, "origin": None, "installRecordReadable": True,
            "signerSha256": ["ab" * 32], "hasLauncherIcon": True, "requestedWatched": sorted(granted), "grantedWatched": sorted(granted)}


def finding(fid, attention="LOOK_FIRST"):
    return {"id": fid, "attention": attention, "confidence": "HIGH", "subject": "x",
            "claim": [{"id": "s", "provenance": "OBSERVED", "confidence": "HIGH", "value": True, "source": "api", "minApi": 14, "limitCount": 1}],
            "context": [], "cannotTellCount": 4, "actionCount": 2, "settings": "SECURITY"}


GRANTED = ["android.permission.READ_SMS", "android.permission.RECEIVE_SMS", "android.permission.READ_CONTACTS",
           "android.permission.RECORD_AUDIO", "android.permission.ACCESS_FINE_LOCATION",
           "android.permission.SYSTEM_ALERT_WINDOW", "android.permission.REQUEST_INSTALL_PACKAGES"]


def good_dump():
    items = [pkg_row("lab.fixture.a11y"), pkg_row("lab.fixture.notif"), pkg_row("lab.fixture.admin"), pkg_row("lab.fixture.ime"),
             pkg_row(PERMS, granted=GRANTED), pkg_row(SYSTEM_APP, system=True, initiating=None)]
    return {
        "signals": [{"id": "special-access.overlay", "availability": "AVAILABLE", "reason": ""},
                    {"id": "special-access.accessibility.enabled", "availability": "AVAILABLE", "reason": ""}],
        "holders": [],
        "findings": [finding("accessibility:" + A11Y), finding("notification-listener:" + NOTIF), finding("device-admin:" + ADMIN),
                     finding("input-method:" + IME, "NOTE"), finding("input-method:" + OTHER_IME, "NOTE"), finding("overlay:" + PERMS), finding("usage-access:" + PERMS),
                     finding("install-unknown-apps:" + PERMS), finding("sms-access:" + PERMS)],
        "apps": [{"pkg": i["pkg"], "tier": "SYSTEM" if i["system"] else "ADB"} for i in items],
        "raw": {
            "accessibility": {"installed": [{"component": A11Y}], "enabledByManager": [A11Y], "enabledBySetting": [A11Y]},
            "notification": {"installed": [{"component": NOTIF}], "enabled": [NOTIF]},
            "admins": {"installed": [{"component": ADMIN, "policyBits": 8}], "active": [ADMIN]},
            "keyboards": {"installed": [{"component": IME}, {"component": OTHER_IME}], "enabled": [IME, OTHER_IME], "default": OTHER_IME},
            "packages": {"count": len(items), "items": items},
            "appOps": {PERMS: dict(ALL_SPECIAL)},
        },
    }


def good_truth():
    def t(granted=(), builtin=False, appops="", installer=None, initiating="com.android.shell"):
        return {"granted": {g: True for g in granted}, "installer": installer, "initiating": initiating, "appops": appops, "builtin": builtin}
    appops = "SYSTEM_ALERT_WINDOW: allow\nGET_USAGE_STATS: allow\nREQUEST_INSTALL_PACKAGES: allow\n"
    fixture = {"signers": ["ab" * 32], "label": None}
    return {
        "all_packages": {"lab.fixture.a11y", "lab.fixture.notif", "lab.fixture.admin", "lab.fixture.ime", PERMS, SYSTEM_APP,
                         "lab.fixture.hidden", "lab.fixture.boot", "lab.fixture.sms"},
        "system_packages": {SYSTEM_APP},
        "must_see": {"lab.fixture.a11y", "lab.fixture.notif", "lab.fixture.admin", "lab.fixture.ime", PERMS},
        "a11y_installed": {A11Y}, "a11y_enabled": {A11Y}, "notif_installed": {NOTIF}, "notif_enabled": {NOTIF},
        "admin_installed": {ADMIN}, "admin_active": {ADMIN}, "admin_policy_bits": 8,
        "ime_installed": {IME, OTHER_IME}, "ime_enabled": {IME, OTHER_IME}, "ime_default": OTHER_IME,
        "packages": {"lab.fixture.a11y": t(), "lab.fixture.notif": t(), "lab.fixture.admin": t(), "lab.fixture.ime": t(),
                     PERMS: t(GRANTED, appops=appops), SYSTEM_APP: t(builtin=True, initiating=None, appops=None)},
        "fixture_facts": {role: dict(fixture) for role in ("a11y", "notif", "admin", "ime", "perms")},
        "fixture_tier_adb": True,
    }


def fixup(dump):
    for p in dump["raw"]["packages"]["items"]:
        p["label"] = p["pkg"]
    return dump


def labels_none(truth):
    for role, r in truth["fixture_facts"].items():
        r["label"] = "lab.fixture." + role
    return truth


class Helpers(unittest.TestCase):
    def test_policy_bits_from_real_xmltree(self):
        tree = "N: android=...\n  E: device-admin (line=2)\n    E: uses-policies (line=3)\n      E: force-lock (line=4)\n"
        self.assertEqual(8, c.parse_policy_bits(tree))
        self.assertEqual(["force-lock"], c.policy_keys(8))

    def test_policy_bits_for_several_policies(self):
        tree = "E: uses-policies\n E: wipe-data\n E: watch-login\n"
        self.assertEqual((1 << 4) | (1 << 1), c.parse_policy_bits(tree))

    def test_ime_list(self):
        text = "lab.fixture.ime/.FixtureIme\ncom.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME\n"
        self.assertEqual({"lab.fixture.ime/lab.fixture.ime.FixtureIme" if False else "lab.fixture.ime/lab.fixture.ime.FixtureIme",
                          "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"}, c.parse_ime_list(text))

    def test_packages(self):
        self.assertEqual({"a.b", "c.d"}, c.parse_packages("package:a.b\npackage:c.d\n"))

    def test_op_mode_listed_and_unlisted(self):
        text = "SYSTEM_ALERT_WINDOW: allow; time=+1s\nGET_USAGE_STATS: ignore\n"
        self.assertEqual("ALLOWED", c.op_mode_name(text, "SYSTEM_ALERT_WINDOW"))
        self.assertEqual("IGNORED", c.op_mode_name(text, "GET_USAGE_STATS"))
        self.assertEqual("DEFAULT", c.op_mode_name(text, "REQUEST_INSTALL_PACKAGES"))

    def test_effective_rule_is_the_lab_d003_rule(self):
        self.assertTrue(c.effective_special("ALLOWED", False))
        self.assertTrue(c.effective_special("DEFAULT", True))
        self.assertFalse(c.effective_special("DEFAULT", False))
        self.assertFalse(c.effective_special("IGNORED", True))

    def test_special_signals_are_unavailable_only_on_android_10(self):
        self.assertEqual("UNAVAILABLE", c.expected_signal_state("29", "special-access.overlay"))
        self.assertEqual("AVAILABLE", c.expected_signal_state("34", "special-access.overlay"))
        self.assertEqual("AVAILABLE", c.expected_signal_state("29", "special-access.accessibility.enabled"))

    def test_device_and_network_signals_are_left_to_the_milestone_3_script(self):
        self.assertIsNone(c.expected_signal_state("34", "device.advanced-protection"))
        self.assertIsNone(c.expected_signal_state("34", "network.wifi-security"))
        self.assertIsNone(c.expected_signal_state("34", "integrity.attestation-chain"))


class Compare(unittest.TestCase):
    def failures(self, dump, truth, api="34"):
        return [x.name for x in c.compare(fixup(dump), labels_none(truth), api) if not x.ok]

    def test_a_faithful_report_passes_everything(self):
        self.assertEqual([], self.failures(good_dump(), good_truth()))

    def test_a_missed_notification_finding_is_caught(self):
        d = good_dump()
        d["findings"] = [f for f in d["findings"] if not f["id"].startswith("notification-listener:")]
        self.assertIn("notification: findings = enabled and installed", self.failures(d, good_truth()))

    def test_a_wrong_app_op_mode_is_caught(self):
        d = good_dump()
        d["raw"]["appOps"][PERMS]["OVERLAY"] = "IGNORED"
        self.assertTrue(any("OVERLAY = appops get" in n for n in self.failures(d, good_truth())))

    def test_an_invented_overlay_finding_is_caught(self):
        d = good_dump()
        d["findings"].append(finding("overlay:made.up"))
        self.assertIn("OVERLAY: findings = effective grants of non-built-in apps", self.failures(d, good_truth()))

    def test_a_visible_hidden_fixture_is_caught(self):
        d = good_dump()
        d["raw"]["packages"]["items"].append(pkg_row("lab.fixture.hidden"))
        self.assertIn("hidden fixtures: not in the inventory (blind spot B1)", self.failures(d, good_truth()))

    def test_a_missing_launcher_package_is_caught(self):
        d = good_dump()
        d["raw"]["packages"]["items"] = [p for p in d["raw"]["packages"]["items"] if p["pkg"] != "lab.fixture.ime"]
        self.assertIn("inventory contains every launcher and capability package", self.failures(d, good_truth()))

    def test_a_wrong_system_flag_is_caught(self):
        d = good_dump()
        d["raw"]["packages"]["items"][-1]["system"] = False
        self.assertIn("system flag = pm list packages -s, for every visible package", self.failures(d, good_truth()))

    def test_a_wrong_grant_is_caught(self):
        d = good_dump()
        d["raw"]["packages"]["items"][4]["grantedWatched"] = ["android.permission.READ_SMS"]
        self.assertIn("granted watched permissions = dumpsys package", self.failures(d, good_truth()))

    def test_shared_uid_packages_are_not_judged_on_grants(self):
        d = good_dump()
        d["raw"]["packages"]["items"][-1]["grantedWatched"] = []
        t = good_truth()
        t["packages"][SYSTEM_APP]["granted"] = {"android.permission.READ_CONTACTS": True}
        t["packages"][SYSTEM_APP]["shared_uid"] = True
        self.assertEqual([], self.failures(d, t))
        t["packages"][SYSTEM_APP]["shared_uid"] = False
        self.assertIn("granted watched permissions = dumpsys package", self.failures(copy.deepcopy(d), t))

    def test_a_wrong_installer_is_caught(self):
        d = good_dump()
        d["raw"]["packages"]["items"][0]["installing"] = "com.android.vending"
        self.assertIn("installer and initiating package = dumpsys package", self.failures(d, good_truth()))

    def test_a_wrong_keyboard_in_use_is_caught(self):
        d = good_dump()
        d["raw"]["keyboards"]["default"] = IME
        self.assertIn("keyboards: in use = settings default_input_method", self.failures(d, good_truth()))

    def test_a_wrong_attention_is_caught(self):
        d = good_dump()
        for f in d["findings"]:
            if f["id"].startswith("overlay:"):
                f["attention"] = "NOTE"
        self.assertIn("fixture finding attention follows the rule", self.failures(d, good_truth()))

    def test_wrong_admin_policies_are_caught(self):
        d = good_dump()
        d["raw"]["admins"]["installed"][0]["policyBits"] = 16
        self.assertIn("admins: declared policies = XML resource", self.failures(d, good_truth()))

    def test_android_ten_refusal_is_expected_and_must_come_with_no_findings(self):
        d = good_dump()
        d["raw"]["appOps"] = {PERMS: "Refused(reason=SecurityException)"}
        d["findings"] = [f for f in d["findings"] if f["id"].split(":")[0] not in ("overlay", "usage-access", "install-unknown-apps")]
        d["signals"] = [{"id": "special-access.overlay", "availability": "UNAVAILABLE", "reason": "x"}]
        # Android 10 has no package visibility rules: the fixtures with no icon and no powers are visible there.
        for pkg in c.HIDDEN_FIXTURES:
            d["raw"]["packages"]["items"].append(pkg_row(pkg))
            d["apps"].append({"pkg": pkg, "tier": "ADB"})
        self.assertEqual([], self.failures(d, good_truth(), api="29"))

    def test_on_android_ten_a_hidden_fixture_that_is_missing_is_caught_and_on_later_versions_one_that_shows_is(self):
        d = good_dump()
        self.assertIn("hidden fixtures: all visible, Android 10 has no package visibility rules", self.failures(d, good_truth(), api="29"))

    def test_a_signal_that_should_be_unavailable_on_android_ten_but_is_not_is_caught(self):
        d = good_dump()
        d["raw"]["appOps"] = {PERMS: "Refused(reason=x)"}
        d["findings"] = [f for f in d["findings"] if f["id"].split(":")[0] not in ("overlay", "usage-access", "install-unknown-apps")]
        self.assertIn("signal special-access.overlay is UNAVAILABLE", self.failures(d, good_truth(), api="29"))

    def test_a_refused_raw_reading_is_reported_not_crashed(self):
        d = copy.deepcopy(good_dump())
        d["raw"]["notification"] = "Refused(reason=nope)"
        self.assertIn("notification: raw reading present", self.failures(d, good_truth()))


if __name__ == "__main__":
    unittest.main()
