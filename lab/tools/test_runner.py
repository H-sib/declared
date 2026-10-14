"""Unit tests for the pure functions in runner.py. Run: python tools/test_runner.py"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import runner  # noqa: E402


def reading(sid, value, code="OK", note=None):
    return {"id": sid, "name": sid, "stage": 1, "code": code, "value": value, "note": note}


class ComponentTests(unittest.TestCase):
    def test_norm_expands_relative_class(self):
        self.assertEqual(runner.norm_component("a.b/.C"), "a.b/a.b.C")

    def test_norm_keeps_absolute_class(self):
        self.assertEqual(runner.norm_component("a.b/x.y.C"), "a.b/x.y.C")

    def test_norm_without_slash_is_unchanged(self):
        self.assertEqual(runner.norm_component("plain"), "plain")

    def test_split_components_handles_null_and_empty(self):
        self.assertEqual(runner.split_components(None), set())
        self.assertEqual(runner.split_components("null"), set())
        self.assertEqual(runner.split_components(""), set())

    def test_split_components_multiple(self):
        got = runner.split_components("a/.A:b/b.B")
        self.assertEqual(got, {"a/a.A", "b/b.B"})


class ParserTests(unittest.TestCase):
    def test_parse_granted(self):
        text = "    android.permission.READ_SMS: granted=true\n    android.permission.CAMERA: granted=false, flags=[ USER_SET]\n"
        self.assertEqual(runner.parse_granted(text), {"android.permission.READ_SMS": True, "android.permission.CAMERA": False})

    def test_parse_installer(self):
        self.assertEqual(runner.parse_installer("  installerPackageName=com.android.vending\n"), "com.android.vending")
        self.assertIsNone(runner.parse_installer("  installerPackageName=null\n"))
        self.assertIsNone(runner.parse_installer("nothing here"))

    def test_parse_admins_componentinfo_form(self):
        text = "Enabled Device Admins (User 0):\n  admin=ComponentInfo{lab.fixture.admin/lab.fixture.FixtureAdminReceiver}\n\nOther section\n"
        self.assertEqual(runner.parse_admins(text), {"lab.fixture.admin/lab.fixture.FixtureAdminReceiver"})

    def test_parse_admins_short_form(self):
        text = "Enabled Device Admins (User 0):\n  lab.fixture.admin/.FixtureAdminReceiver:\n    uid=10200\n"
        self.assertEqual(runner.parse_admins(text), {"lab.fixture.admin/lab.fixture.admin.FixtureAdminReceiver"})

    def test_parse_admins_empty(self):
        self.assertEqual(runner.parse_admins(""), set())

    def test_settings_diff_and_parse(self):
        before = {"secure": runner.parse_settings_list("a=1\nb=2\n")}
        after = {"secure": runner.parse_settings_list("a=1\nb=3\nc=9\n")}
        diff = runner.settings_diff(before, after)
        self.assertEqual([(d["key"], d["before"], d["after"]) for d in diff], [("b", "2", "3"), ("c", None, "9")])


class SettingsRedactionTests(unittest.TestCase):
    def test_identifying_values_are_blanked_but_keys_are_kept(self):
        dump = {"secure": {"android_id": "abc123", "bluetooth_address": "AA:BB", "lock_pattern": "1"},
                "global": {"device_name": "Hasib's phone", "wifi_on": "1"}}
        out = runner.redact_settings(dump)
        self.assertEqual(out["secure"]["android_id"], "<redacted>")
        self.assertEqual(out["secure"]["bluetooth_address"], "<redacted>")
        self.assertEqual(out["global"]["device_name"], "<redacted>")
        self.assertEqual(out["secure"]["lock_pattern"], "1")
        self.assertEqual(out["global"]["wifi_on"], "1")

    def test_diff_still_works_on_redacted_dumps(self):
        before = runner.redact_settings({"secure": {"android_id": "a", "x": "1"}})
        after = runner.redact_settings({"secure": {"android_id": "b", "x": "2"}})
        self.assertEqual([d["key"] for d in runner.settings_diff(before, after)], ["x"])


class CommandTests(unittest.TestCase):
    def test_enable_a11y_merges_with_existing(self):
        cmds = runner.enable_commands("a11y", "other.pkg/other.pkg.Svc")
        self.assertIn("other.pkg/other.pkg.Svc", cmds[0])
        self.assertIn("lab.fixture.a11y/lab.fixture.FixtureA11yService", cmds[0])
        self.assertEqual(cmds[1], "settings put secure accessibility_enabled 1")

    def test_disable_a11y_removes_only_fixture(self):
        current = "other.pkg/other.pkg.Svc:lab.fixture.a11y/lab.fixture.FixtureA11yService"
        cmds = runner.disable_commands("a11y", current)
        self.assertEqual(cmds, ["settings put secure enabled_accessibility_services other.pkg/other.pkg.Svc"])

    def test_disable_a11y_last_service_clears_setting(self):
        cmds = runner.disable_commands("a11y", "lab.fixture.a11y/lab.fixture.FixtureA11yService")
        self.assertEqual(cmds[0], "settings delete secure enabled_accessibility_services")

    def test_unknown_role_has_no_commands(self):
        self.assertEqual(runner.enable_commands("hidden"), [])
        self.assertEqual(runner.disable_commands("hidden"), [])

    def test_perms_enable_and_disable_are_symmetric(self):
        on, off = runner.enable_commands("perms"), runner.disable_commands("perms")
        self.assertEqual(len(on), len(off))
        self.assertTrue(all(c.startswith(("pm grant", "appops set")) for c in on))
        self.assertTrue(all(c.startswith(("pm revoke", "appops set")) for c in off))


class ComparatorTests(unittest.TestCase):
    truth = {
        "getprop.security_patch": "2026-09-05", "getprop.sdk": "34", "getprop.release": "14",
        "global.adb_enabled": "1", "global.adb_wifi_enabled": None, "global.development_settings_enabled": "1",
        "secure.enabled_accessibility_services": "lab.fixture.a11y/lab.fixture.FixtureA11yService",
        "secure.enabled_notification_listeners": None,
        "secure.default_input_method": "com.x/.Ime",
        "global.private_dns_mode": "hostname", "global.private_dns_specifier": "dns.example",
        "dumpsys.device_policy": "",
    }

    def test_d1_match_and_mismatch(self):
        self.assertTrue(runner.cmp_d1(reading("D1", {"securityPatch": "2026-09-05"}), self.truth)[0])
        self.assertFalse(runner.cmp_d1(reading("D1", {"securityPatch": "2025-01-01"}), self.truth)[0])

    def test_non_ok_reading_is_not_compared(self):
        self.assertIsNone(runner.cmp_d1(reading("D1", None, code="SECURITY_EXCEPTION"), self.truth)[0])
        self.assertIsNone(runner.cmp_d1(None, self.truth)[0])

    def test_d4_and_missing_setting_maps_to_minus_one(self):
        self.assertTrue(runner.cmp_d4(reading("D4", {"adb_enabled": 1}), self.truth)[0])
        self.assertTrue(runner.cmp_d5(reading("D5", {"adb_wifi_enabled": -1}), self.truth)[0])

    def test_p1_set_comparison_reports_missing_and_extra(self):
        ok = reading("P1", {"enabled": [{"id": "lab.fixture.a11y/.FixtureA11yService"}]})
        # relative class in the probe value normalises against the package, not the fixture namespace, so it differs
        self.assertFalse(runner.cmp_p1(ok, self.truth)[0])
        good = reading("P1", {"enabled": [{"id": "lab.fixture.a11y/lab.fixture.FixtureA11yService"}]})
        self.assertTrue(runner.cmp_p1(good, self.truth)[0])

    def test_p3_empty_matches_null(self):
        self.assertTrue(runner.cmp_p3(reading("P3", {"secureSetting": None}), self.truth)[0])

    def test_n2_hostname_mode(self):
        r = reading("N2", {"privateDnsActive": True, "privateDnsServerName": "dns.example"})
        self.assertTrue(runner.cmp_n2(r, self.truth)[0])
        bad = reading("N2", {"privateDnsActive": True, "privateDnsServerName": "other"})
        self.assertFalse(runner.cmp_n2(bad, self.truth)[0])

    def test_n2_opportunistic_is_not_matched(self):
        t = dict(self.truth, **{"global.private_dns_mode": "opportunistic"})
        self.assertIsNone(runner.cmp_n2(reading("N2", {"privateDnsActive": True}), t)[0])

    def test_k8_checks_the_sha3_test_vector(self):
        good = reading("K8", {"platformSha3": {"available": True, "abcDigest": runner.SHA3_256_ABC}})
        self.assertTrue(runner.cmp_k8(good, {})[0])
        bad = reading("K8", {"platformSha3": {"available": True, "abcDigest": "00"}})
        self.assertFalse(runner.cmp_k8(bad, {})[0])
        absent = reading("K8", {"platformSha3": {"available": False}})
        self.assertIsNone(runner.cmp_k8(absent, {})[0])


class AppOpsAndPermissionTests(unittest.TestCase):
    appops_text = ("SYSTEM_ALERT_WINDOW: allow; time=+3m ago\n"
                   "REQUEST_INSTALL_PACKAGES: allow\n"
                   "GET_USAGE_STATS: ignore\n"
                   "COARSE_LOCATION: foreground; rejectTime=+1d ago\n")

    def test_parse_appops_maps_modes(self):
        ops = runner.parse_appops(self.appops_text)
        self.assertEqual(ops["SYSTEM_ALERT_WINDOW"], 0)
        self.assertEqual(ops["GET_USAGE_STATS"], 1)
        self.assertEqual(ops["COARSE_LOCATION"], 4)
        self.assertNotIn("READ_SMS", ops)

    def test_parse_appops_ignores_noise(self):
        self.assertEqual(runner.parse_appops("No operations.\n"), {})
        self.assertEqual(runner.parse_appops(None), {})

    def probe_p7(self, **overrides):
        row = {"systemAlertWindow": 0, "getUsageStats": 1, "requestInstallPackages": 0, "readSms": 3}
        row.update(overrides)
        return reading("P7", {"rows": {runner.PERMS_FIXTURE: row}})

    def test_p7_matches_when_unlisted_ops_are_default(self):
        truth = {f"appops.{runner.PERMS_FIXTURE}": self.appops_text}
        ok, note = runner.cmp_p7(self.probe_p7(), truth)
        self.assertTrue(ok, note)

    def test_p7_reports_the_differing_op(self):
        truth = {f"appops.{runner.PERMS_FIXTURE}": self.appops_text}
        ok, note = runner.cmp_p7(self.probe_p7(readSms=0), truth)
        self.assertFalse(ok)
        self.assertIn("READ_SMS", note)

    def test_p7_permission_backed_op_is_allowed_when_permission_granted(self):
        # Regression: 'appops get' omits READ_SMS until it is used, so a granted permission must imply allow (0).
        truth = {f"appops.{runner.PERMS_FIXTURE}": self.appops_text,
                 f"dumpsys.package.{runner.PERMS_FIXTURE}": "    android.permission.READ_SMS: granted=true\n"}
        ok, note = runner.cmp_p7(self.probe_p7(readSms=0), truth)
        self.assertTrue(ok, note)

    def test_p7_refused_read_is_unreadable_not_a_mismatch(self):
        # Regression from the API 29 sweep: cross-app app-ops threw SecurityException there.
        r = self.probe_p7(systemAlertWindow="ERR:SecurityException", readSms="ERR:SecurityException")
        match, note = runner.cmp_p7(r, {f"appops.{runner.PERMS_FIXTURE}": self.appops_text})
        self.assertIsNone(match)
        self.assertIn("not readable", note)

    def test_p7_without_fixture_row_is_not_compared(self):
        self.assertIsNone(runner.cmp_p7(reading("P7", {"rows": {}}), {})[0])

    def test_p9_compares_granted_sensitive_permissions(self):
        dump = ("    android.permission.READ_SMS: granted=true\n"
                "    android.permission.RECORD_AUDIO: granted=false\n"
                "    android.permission.INTERNET: granted=true\n")
        truth = {f"dumpsys.package.{runner.PERMS_FIXTURE}": dump}
        good = reading("P9", {"rows": [{"package": runner.PERMS_FIXTURE, "granted": ["READ_SMS"]}]})
        self.assertTrue(runner.cmp_p9(good, truth)[0])
        bad = reading("P9", {"rows": [{"package": runner.PERMS_FIXTURE, "granted": ["READ_SMS", "RECORD_AUDIO"]}]})
        self.assertFalse(runner.cmp_p9(bad, truth)[0])

    def test_p5_unreadable_default_is_not_compared(self):
        r = reading("P5", {"defaultInputMethod": "ERR:SecurityException:nope"})
        self.assertIsNone(runner.cmp_p5(r, {"secure.default_input_method": "a/.B"})[0])

    def test_v2_installer_comparison(self):
        truth = {"dumpsys.package.lab.fixture.a11y": "installerPackageName=null\n"}
        r = reading("V2", {"rows": [{"package": "lab.fixture.a11y", "installer": None, "tier": "adb-or-unknown"}]})
        self.assertTrue(runner.cmp_v2_installer(r, truth)[0])
        r2 = reading("V2", {"rows": [{"package": "lab.fixture.a11y", "installer": "com.android.vending", "tier": "store"}]})
        self.assertFalse(runner.cmp_v2_installer(r2, truth)[0])


class SetupExpectationTests(unittest.TestCase):
    enabled_truth = {
        "secure.enabled_accessibility_services": "lab.fixture.a11y/lab.fixture.FixtureA11yService",
        "secure.enabled_notification_listeners": "x/y:lab.fixture.notif/lab.fixture.FixtureNotifListener",
        "secure.enabled_input_methods": "lab.fixture.ime/lab.fixture.FixtureIme:other/.Ime",
        "dumpsys.device_policy": "Enabled Device Admins:\n  lab.fixture.admin/lab.fixture.FixtureAdminReceiver:\n",
        f"dumpsys.package.{runner.PERMS_FIXTURE}": "    android.permission.READ_SMS: granted=true\n",
    }

    def test_all_roles_present_when_everything_is_enabled(self):
        for role in runner.ALL_ROLES:
            self.assertTrue(runner.role_present(role, self.enabled_truth), role)

    def test_all_roles_absent_on_empty_truth(self):
        for role in runner.ALL_ROLES:
            self.assertFalse(runner.role_present(role, {}), role)

    def test_unknown_role_is_none(self):
        self.assertIsNone(runner.role_present("hidden", self.enabled_truth))

    def test_silent_admin_setup_failure_is_caught(self):
        # The vacuous-match case: the admin was never activated, so probe and truth would both be empty.
        truth = dict(self.enabled_truth, **{"dumpsys.device_policy": "No admins.\n"})
        failures = runner.check_expectations(runner.ALL_ROLES, [], truth)
        self.assertEqual([f[0] for f in failures], ["admin"])

    def test_baseline_expectation_catches_a_leftover_role(self):
        failures = runner.check_expectations([], runner.ALL_ROLES, self.enabled_truth)
        self.assertEqual(len(failures), len(runner.ALL_ROLES))

    def test_label_inference(self):
        self.assertEqual(runner.LABEL_EXPECTATIONS["all-enabled-adb"][0], runner.ALL_ROLES)
        self.assertEqual(runner.LABEL_EXPECTATIONS["baseline-empty"][1], runner.ALL_ROLES)

    def test_expectation_rows_report_failed_and_ok(self):
        truth = dict(self.enabled_truth, **{"dumpsys.device_policy": ""})
        rows = runner.expectation_rows(["a11y", "admin"], [], truth, {"sdk": 31, "display": "d"}, "s", "dev")
        by = {r["signal_id"]: r["result_code"] for r in rows}
        self.assertEqual(by, {"SETUP:a11y": "OK", "SETUP:admin": "FAILED"})
        for r in rows:
            self.assertEqual(list(r.keys()), runner.CSV_FIELDS)


class RowTests(unittest.TestCase):
    def doc(self):
        return {
            "meta": {"sdk": 34, "display": "UP1A.1", "applicationId": "lab.probe.q", "targetSdk": 36},
            "readings": [
                reading("D1", {"securityPatch": "2026-09-05"}),
                reading("D3", {"isDeviceSecure": True}),
                reading("N6", {"permFine": True, "permNearby": False, "locationEnabled": True}),
                reading("D7", None, code="UNSUPPORTED", note="needs API 36"),
            ],
        }

    def test_build_rows_covers_every_reading(self):
        truth = {"getprop.security_patch": "2026-09-05"}
        rows = runner.build_rows(self.doc(), truth, "baseline", "SM-G990B")
        self.assertEqual([r["signal_id"] for r in rows], ["D1", "D3", "N6", "D7"])
        self.assertEqual(rows[0]["ground_truth_match"], "true")
        self.assertEqual(rows[1]["ground_truth_match"], "n/a")
        self.assertEqual(rows[3]["result_code"], "UNSUPPORTED")
        self.assertIn("needs API 36", rows[3]["notes"])
        self.assertEqual(rows[0]["permission_state"], "fine=1 nearby=0 location=1")

    def test_redacted_runs_are_never_reported_as_mismatches(self):
        # Regression: hashed identifiers were compared with plaintext truth and reported as false mismatches.
        doc = self.doc()
        doc["meta"]["redact"] = True
        doc["readings"][0] = reading("D1", {"securityPatch": "something else entirely"})
        rows = runner.build_rows(doc, {"getprop.security_patch": "2026-09-05"}, "s", "d")
        self.assertTrue(all(r["ground_truth_match"] == "n/a" for r in rows))
        self.assertIn("redacted run", rows[0]["notes"])

    def test_rows_match_csv_schema(self):
        rows = runner.build_rows(self.doc(), {"getprop.security_patch": "x"}, "s", "d")
        for r in rows:
            self.assertEqual(list(r.keys()), runner.CSV_FIELDS)

    def test_summarize_truncates(self):
        s = runner.summarize({"k": "x" * 500}, limit=50)
        self.assertEqual(len(s), 50)
        self.assertTrue(s.endswith("..."))


if __name__ == "__main__":
    unittest.main(verbosity=2)
